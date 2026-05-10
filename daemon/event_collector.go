package main

import (
	"bytes"
	"container/list"
	"encoding/binary"
	"errors"
	"log"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	"github.com/cilium/ebpf/rlimit"
)

// 与 eBPF C 端的 binder_transaction.byte.c 保持一致
const (
	ChunkSize = 0x400
	MaxChunks = 16

	// stack record magic(spec § 4.1.2),与 BPF C 端 STACK_RECORD_MAGIC 严格相等。
	// daemon 按 ringbuf record 前 8 字节(LE 解读)区分 base / stack record;
	// 取值域不可达保证(高位 0xDEADBEEFDEADBEEF 远超 pid+uid 上限)。
	StackRecordMagic uint64 = 0xDEADBEEFDEADBEEF
)

// 事件 kind tag(与 BPF 端 EVT_KIND_* 严格一致,ringbuf record 第 1 字节)
const (
	evtKindMain     uint8 = 1
	evtKindSideband uint8 = 2
	// evtKindCookie:来自 cookie 旁路 kprobe 的 sideband(spec daemon-bpf-load-known-issue
	// 通用方案)。daemon 按 transaction_id 把 cookie 注入主事件 meta.cookie 字段,
	// 后续才进入 § 5.6.3 状态机。
	evtKindCookie uint8 = 3
)

// tx_flags 位标志(与 binder.h 一致)。TF_ONE_WAY 是 daemon "oneway vs SYNC" 分流唯一信号源
// (spec § 5.6.3 强制规范),不允许靠 "reply 没到" 反推 oneway。
const (
	txFlagOneWay uint32 = 0x01
)

// 事务缓冲过期策略
const (
	staleBufferTTL    = 5 * time.Second // 超过 TTL 仍未集齐的分片视为丢失,清理掉
	cleanupEveryNEvts = 256             // 每处理 N 个事件触发一次清理

	// pendingStack TTL = staleBufferTTL = 5s(回填 nitpicker M2)
	//
	// 之前误用 100ms TTL:spec § 4.1.2 假设的"stack reserve 与 base reserve 间隔 < µs"
	// 只对**第一个 base chunk** 成立;真实情况是 base record 分片(最多 16 chunk),
	// daemon 端在 chunks 全到齐 + complete=true 时才调 takePendingStack(line 476)。
	// 大 parcel(10 KB+)在高 ringbuf 压力下分片到齐间隔可能超过 100 ms,会让
	// pendingStackTTL evict 把已到 stack 当孤儿删掉,大 parcel 路径系统性丢栈,
	// 错误归因到 stackRecordOrphanCount。
	//
	// 修法:把 pendingStack TTL 与 staleBufferTTL 对齐(都是 5s)。语义:base 路径
	// 在 staleBufferTTL 内还没集齐就视为整事件丢失,stack 在同样时间内未与 base 合并
	// 才算孤儿。两条 TTL 同步生效,跨分片大 parcel 不会误清栈。
	pendingStackTTL = staleBufferTTL
)

// § 5.6.3 / § 5.6.4 状态机参数
const (
	pendingMainTTL          = 5 * time.Second // SYNC 主等 sideband 的最长窗口
	pendingMainCapacity     = 16384           // pendingMain LRU 上限,见 spec § 5.6.4 sizing 推导
	pendingOnewayTTL        = 1 * time.Second // oneway sideband 早到等主的窗口
	pendingOnewayCapacity   = 4096            // pendingOneway LRU 上限
	recentOnewayEmitTTL     = 1 * time.Second // oneway 主已 emit 后追踪晚到 sideband 的窗口
	recentOnewayCapacity    = 4096            // recentOnewayEmit LRU 上限
	stateMachineSweepPeriod = 250 * time.Millisecond

	// recentCookies 是 cookie 重复检测环形 set 的容量。
	// 用于 sanity:同一 cookie 不应在主记录中出现两次(BPF 端 __sync_fetch_and_add
	// 全局原子分配,正常情况下不可能复用)。N=4096 远大于秒级 TPS 峰值 × 队列延迟。
	recentCookieRingSize = 4096

	// pendingCookieByTxid TTL:cookie sideband 先到、主事件后到时,cookie 暂存的
	// 上限窗口。与 pendingMainTTL 对齐(5s):主事件 5s 内必到否则视为孤儿。
	// 见 spec daemon-bpf-load-known-issue 通用方案。
	pendingCookieTTL = staleBufferTTL

	// pendingCookieByTxid LRU 容量上限(M3 修复):防止高 TPS / 异常洪水把 daemon
	// 内存吃满。参考 pendingMainCapacity sizing(spec § 5.6.4):16384 ≈ 5s × 3000 TPS,
	// 容纳秒级 burst。LRU evict 走双向链表 O(1)。
	pendingCookieCapacity = 16384
)

// pairFailureCounters 是 § 5.6.3 5 个失败 bucket(daemon 4 个 + sanity check 1 个 +
// 已知噪声 1 个)的 daemon 侧实现。app 侧 tx_failed_after_sideband 不在这里。
type pairFailureCounters struct {
	entryEarlyReturn         atomic.Uint64 // pendingMain 5s TTL/LRU evict、pending_map LRU evict 派生
	sidebandOrphan           atomic.Uint64 // sideband 三处 lookup 全 miss 且 1s 后仍无主
	ringbufOverflowMain      atomic.Uint64 // BPF 端主 reserve 失败计数(daemon 周期 read 汇总)
	ringbufOverflowSideband  atomic.Uint64 // BPF 端 sideband reserve 失败计数
	cookieMonotonicViolation atomic.Uint64 // cookie 单调性 sanity check,理论 0
	onewaySidebandLate       atomic.Uint64 // 已知噪声,不进 5 个失败 bucket(spec § 5.6.3)
}

// txMeta 是同一个 transaction 跨分片需要保留的"首次出现即定型"的元数据。
// BPF 端每个分片都会写这些字段(因为 ringbuf event 是独立 reserve 的),
// 但语义上它们只属于该事务,first-seen 即为权威值;后续分片忽略,避免被覆写。
type txMeta struct {
	pid        uint32
	uid        uint32
	code       uint32
	flags      uint32
	dataSize   uint64
	isReply    uint8
	binderDev  uint8
	targetKind uint8
	toPid      uint32
	toUid      uint32
	targetRef  uint64
	pairId     uint64
	cookie     uint64 // P4-B-2:BPF entry kprobe 分配的桥接 cookie(reply 路径仍 0)
}

// txBuffer 单个 binder transaction 的分片缓冲
type txBuffer struct {
	firstSeenNs int64
	chunks      [][]byte
	meta        txMeta
}

// pendingStackEntry pending stack record(等待与 base 合并,spec § 4.1.2)
type pendingStackEntry struct {
	stack      *bpfStackTraceEvent
	receivedNs int64
}

// pendingMainEntry 是 SYNC 主等 sideband 期间存在 pendingMain 的状态。
//
// spec 2026-05-09 daemon-cookie-race-fix:cookie 字段冗余存放,用于 cookie==0 SYNC
// 暂存于 pendingMainByTxid 后被反向激活 → 写真值 cookie + 转挂 pendingMain[cookie]。
// 走原 cookie!=0 路径的 entry,该字段与 map key 相同,纯冗余(便于 evict 路径不必
// 再绕 LRU.Value 拿 cookie)。
type pendingMainEntry struct {
	enqueuedNs int64
	event      *BinderEventPayload
	cookie     uint64
	listElem   *list.Element // LRU 双向链表节点指针,evict 时 O(1)
}

// pendingOnewayEntry 是 oneway sideband 早到等主的状态。
type pendingOnewayEntry struct {
	enqueuedNs       int64
	kernelDebugId    uint64
	toPid            uint32
	toUid            uint32
	toUidUnsupported uint8
	listElem         *list.Element
}

// recentOnewayEntry 仅追踪 cookie + 时间,无副作用 evict。
type recentOnewayEntry struct {
	enqueuedNs int64
	listElem   *list.Element
}

// pendingCookieEntry pending cookie sideband(等待与主事件合并,
// spec daemon-bpf-load-known-issue 通用方案)。listElem 指向 pendingCookieLRU
// 双向链表节点,evict 时 O(1)。
type pendingCookieEntry struct {
	cookie     uint64
	receivedNs int64
	listElem   *list.Element
}

// EventCollector eBPF事件收集器
type EventCollector struct {
	logger   *log.Logger
	objs     *bpfObjects
	kp       link.Link
	kpCookie link.Link // spec daemon-bpf-load-known-issue:cookie 旁路 kprobe
	rawTp    link.Link
	rd       *ringbuf.Reader

	// 事件回调
	onEvent func(event *BinderEventPayload)

	// 状态
	running   atomic.Bool
	targetUid atomic.Uint32

	// 事务缓冲(用于合并分片)
	transactionBuffers map[uint64]*txBuffer
	bufferMu           sync.Mutex
	eventCounter       uint32 // 用于触发周期性清理

	// 调用栈反符号化器(可选;Init 失败时 nil → 不影响主路径)
	symbolizer *Symbolizer

	// pendingStack:stack record 先到、base 后到时,暂存以待合并(spec § 4.1.2)
	pendingStack   map[uint64]*pendingStackEntry
	pendingStackMu sync.Mutex

	// pendingCookieByTxid:cookie sideband 先到、主事件第一个 chunk 后到时,暂存
	// cookie 等主事件第一次见到 transaction_id 时注入 meta(spec
	// daemon-bpf-load-known-issue 通用方案)。键 = transaction_id(主 program 与
	// cookie program 通过 BPF tid_to_txid_map 桥接的精确关联键)。
	//
	// M4 修复:用**独立** pendingCookieMu 保护(不与 bufferMu 共享)。
	// 原设计共用 bufferMu,但 handleEvent 在 bufferMu 内会调 routeMainEvent →
	// flushEmissions → onEvent,onEvent 走 SocketServer 推送,客户端慢/断时阻塞,
	// 进而冻结 sweepLoop 对 pendingCookieByTxid 的 TTL evict。
	// 锁顺序约定:bufferMu → pendingCookieMu(单向取得),禁反向。
	pendingCookieByTxid map[uint64]*pendingCookieEntry
	// LRU 双向链表(M3 修复):evict 时 O(1) 推后端。容量上限 pendingCookieCapacity。
	pendingCookieLRU *list.List
	pendingCookieMu  sync.Mutex

	// 调用栈 telemetry
	stackRecordOrphanCount atomic.Uint64 // stack 100ms 内无 base 到达 → 孤儿
	baseRecordNoStackCount atomic.Uint64 // base 完成但 pendingStack 无栈 → 无栈事件
	stackReserveFailCount  atomic.Uint64 // BPF 端 stack reserve 失败(本期 daemon 端无法直接观测,留位)

	// § 5.6.3 三个 cookie-索引 map + LRU 链表;同一把锁保护
	stateMu          sync.Mutex
	pendingMain      map[uint64]*pendingMainEntry
	pendingMainLRU   *list.List // front = MRU, back = LRU
	pendingOneway    map[uint64]*pendingOnewayEntry
	pendingOnewayLRU *list.List
	recentOneway     map[uint64]*recentOnewayEntry
	recentOnewayLRU  *list.List

	// spec 2026-05-09 daemon-cookie-race-fix:cookie==0 SYNC 主事件先于 cookie
	// sideband 出队时,挂到 pendingMainByTxid(key = transactionId)等 cookie sideband
	// 反向激活 → 转挂 pendingMain[cookie] 等 raw_tp sideband。修补嫌疑 #4 race
	// (单 chunk SYNC request 几乎 100% 命中)。复用 pendingMainTTL 与 pendingMainCapacity。
	pendingMainByTxid    map[uint64]*pendingMainEntry
	pendingMainTxidLRU   *list.List

	// cookie 重复检测(取代旧的"乱序到达"单调 check —— 后者在 multi-CPU
	// per-CPU ringbuf 出队下必然误报,见 review B1)。
	// 实现:固定大小环 + set,O(1) 写入和查重。容量 recentCookieRingSize。
	recentCookieSet  map[uint64]struct{}
	recentCookieRing []uint64
	recentCookieHead int

	failures pairFailureCounters

	// 测试钩子:state machine sweeper 用 nowFn 拿"当前时间",方便单测注入快进
	nowFn func() time.Time

	// 测试钩子:legacyCookieZeroSyncEmit=true 时,cookie==0 SYNC 主事件**直接 emit**
	// (沿用 spec 2026-05-09 daemon-cookie-race-fix 之前的降级语义)。生产路径常驻
	// false:cookie==0 SYNC 必须挂 pendingMainByTxid 等 cookie sideband。
	//
	// 用途:存量 handleEvent / chunk 重组测试用例(makeChunk 默认 cookie=0 + IsReply=0 +
	// 非 oneway)在新状态机下不会立即 emit,要么改测试 nowFn 注入快进让 5s TTL 触发,
	// 要么走这条"旧语义"钩子保持测试简洁。新加的 state machine 测试用例显式设 false
	// 验证新路径。
	legacyCookieZeroSyncEmit bool

	// sweeper goroutine 控制
	sweepStop chan struct{}
	sweepDone chan struct{}
}

// NewEventCollector 创建事件收集器
func NewEventCollector(logger *log.Logger) *EventCollector {
	return &EventCollector{
		logger:              logger,
		transactionBuffers:  make(map[uint64]*txBuffer),
		pendingStack:        make(map[uint64]*pendingStackEntry),
		pendingCookieByTxid: make(map[uint64]*pendingCookieEntry),
		pendingCookieLRU:    list.New(),
		pendingMain:         make(map[uint64]*pendingMainEntry),
		pendingMainLRU:      list.New(),
		pendingMainByTxid:   make(map[uint64]*pendingMainEntry),
		pendingMainTxidLRU:  list.New(),
		pendingOneway:       make(map[uint64]*pendingOnewayEntry),
		pendingOnewayLRU:    list.New(),
		recentOneway:        make(map[uint64]*recentOnewayEntry),
		recentOnewayLRU:     list.New(),
		recentCookieSet:     make(map[uint64]struct{}, recentCookieRingSize),
		recentCookieRing:    make([]uint64, recentCookieRingSize),
		nowFn:               time.Now,
	}
}

// SetSymbolizer 注入反符号化器。Init 失败时不调用 → 主路径仍工作,只是 stackTrace 为空。
func (c *EventCollector) SetSymbolizer(s *Symbolizer) {
	c.symbolizer = s
}

// SetEventCallback 设置事件回调
func (c *EventCollector) SetEventCallback(callback func(event *BinderEventPayload)) {
	c.onEvent = callback
}

// Start 启动收集器。BPF 加载失败时优雅 fallback:不返回 error,只打 warning,
// daemon 继续启动 socket server,允许 app 端连接做 UI 验证(不抓事件)。
// 实现 spec § 4.1.4 "若 bpf_get_stack(BPF_F_USER_STACK) 在目标内核 verifier 拒绝
//                   ...daemon 启动 socket 报告 capability=false"约定。
func (c *EventCollector) Start() error {
	// 允许当前进程锁定eBPF资源所需的内存
	if err := rlimit.RemoveMemlock(); err != nil {
		return err
	}

	// 加载预编译的程序和映射到内核。
	// 启用 LogLevelStats + 大 LogSize 拿完整 verifier log,方便诊断真机加载失败
	// (spec daemon-bpf-load-known-issue 要求)。LogLevelInstruction 会让 verifier
	// 输出每条指令的寄存器状态,数据量极大;只在加载失败时按需开启。
	c.objs = &bpfObjects{}
	loadOpts := &ebpf.CollectionOptions{
		Programs: ebpf.ProgramOptions{
			LogLevel: ebpf.LogLevelStats,
			LogSize:  1 << 24, // 16MB,满足 truncated 探测
		},
	}
	if err := loadBpfObjects(c.objs, loadOpts); err != nil {
		var verErr *ebpf.VerifierError
		if errors.As(err, &verErr) {
			// %+v 格式输出完整 log;truncated 字段反映 LogSize 是否吃满
			c.logger.Printf("BPF verifier error (full log, truncated=%v):\n%+v",
				verErr.Truncated, verErr)
		}
		c.logger.Printf("BPF 加载失败,daemon 优雅 fallback 到无监控模式(socket server 仍启动): %v", err)
		c.objs = nil // 标记不可用,Stop 时跳过 Close
		return nil   // 不返回 error,让上层继续启 socket server
	}

	// 若 BPF 加载已 fallback,跳过后续 attach
	if c.objs == nil {
		return nil
	}

	// 初始化配置(默认不过滤,uid=0表示监控所有)
	var configKey uint32 = 0
	configValue := bpfTraceConfig{Uid: 0}
	if err := c.objs.TraceConfigMap.Put(configKey, configValue); err != nil {
		c.objs.Close()
		c.objs = nil
		c.logger.Printf("TraceConfigMap.Put 失败,fallback: %v", err)
		return nil
	}

	// 调用栈采集已固定为全量 always-on(BPF 端 stack_sample_config map + 滚动窗口
	// 采样路径已删除)。无需 daemon 端再写任何运行时开关。

	// 挂载顺序经真机 trace_pipe 验证(B5 修复,2026-05-03):
	// kernel 5.15.74 的 kprobe perf_event 多 program 链表是 **LIFO**(后 attach 先跑),
	// 不是注释里之前误传的 FIFO。所以为了让主 program **先**触发(把 transaction_id
	// 写入 tid_to_txid_map[tid]),我们必须**后**attach 主 program。
	//
	// 真机 trace_pipe 摘录(commit message 表格):
	//   <event: binder syscall on tid X>
	//     bpf_trace_printk: BTRACE_ATTACH_ORDER cookie_kprobe tid=X ktime=T1
	//     bpf_trace_printk: BTRACE_ATTACH_ORDER main_kprobe   tid=X ktime=T2 (T2 > T1)
	// 若 cookie 先 attach、main 后 attach,实际触发顺序就是 main → cookie。
	//
	// 旁路 cookie kprobe(spec daemon-bpf-load-known-issue 通用方案):
	// 与主 program 同一 hook 点,极简 SEC 只做 cookie=ktime + pending_map[tid]=cookie
	// + 上送 EVT_KIND_COOKIE sideband。daemon 端按 transaction_id 把 cookie 注入主事件。
	kpCookie, err := link.Kprobe("binder_transaction", c.objs.KprobeBinderTransactionCookie, nil)
	if err != nil {
		c.objs.Close()
		return err
	}
	c.kpCookie = kpCookie

	// 主 entry kprobe(等价 P4-B-1 baseline):**最后** attach 让它在 LIFO 链表头部,
	// 先于 cookie kprobe 触发,先写 tid_to_txid_map[tid]=transaction_id,cookie program
	// 紧随其后 lookup 拿到精确关联键。
	kp, err := link.Kprobe("binder_transaction", c.objs.KprobeBinderTransaction, nil)
	if err != nil {
		c.kpCookie.Close()
		c.objs.Close()
		return err
	}
	c.kp = kp

	// 挂载 raw_tracepoint(P4-B-2+3 一站式 sideband 探针,spec § 5.6.2)
	rawTp, err := link.AttachRawTracepoint(link.RawTracepointOptions{
		Name:    "binder_transaction",
		Program: c.objs.RawTpBinderTransaction,
	})
	if err != nil {
		c.kpCookie.Close()
		c.kp.Close()
		c.objs.Close()
		return err
	}
	c.rawTp = rawTp

	// 打开ringbuf reader
	rd, err := ringbuf.NewReader(c.objs.TraceEventMap)
	if err != nil {
		c.rawTp.Close()
		c.kpCookie.Close()
		c.kp.Close()
		c.objs.Close()
		return err
	}
	c.rd = rd

	c.running.Store(true)
	c.logger.Println("eBPF事件收集器已启动")

	// 启动事件读取协程
	go c.readLoop()
	// 启动状态机 sweeper(TTL/LRU evict + ringbuf overflow 计数同步)
	c.sweepStop = make(chan struct{})
	c.sweepDone = make(chan struct{})
	go c.sweepLoop()

	return nil
}

// Stop 停止收集器
func (c *EventCollector) Stop() error {
	c.running.Store(false)

	if c.sweepStop != nil {
		close(c.sweepStop)
		<-c.sweepDone
		c.sweepStop = nil
	}

	if c.rd != nil {
		c.rd.Close()
	}
	if c.rawTp != nil {
		c.rawTp.Close()
	}
	if c.kpCookie != nil {
		c.kpCookie.Close()
	}
	if c.kp != nil {
		c.kp.Close()
	}
	if c.objs != nil {
		c.objs.Close()
	}

	c.logger.Println("eBPF事件收集器已停止")
	return nil
}

// SetTargetUid 设置目标UID进行过滤
func (c *EventCollector) SetTargetUid(uid uint32) error {
	c.targetUid.Store(uid)

	// 更新BPF配置
	if c.objs != nil {
		var configKey uint32 = 0
		configValue := bpfTraceConfig{Uid: uid}
		if err := c.objs.TraceConfigMap.Put(configKey, configValue); err != nil {
			return err
		}
	}

	c.logger.Printf("目标UID设置为: %d", uid)
	return nil
}

// readLoop 事件读取循环 —— 单 ringbuf,先按前 8 字节 LE magic 识别 stack record
// (取值域不可达,见 spec § 4.1.2),非 stack record 再按第 1 字节 kind 分流到
// 主事件 / sideband 处理路径(spec § 5.6.3)。
func (c *EventCollector) readLoop() {
	for c.running.Load() {
		record, err := c.rd.Read()
		if err != nil {
			if errors.Is(err, ringbuf.ErrClosed) {
				c.logger.Println("Ringbuf已关闭,退出读取循环")
				return
			}
			c.logger.Printf("读取ringbuf失败: %v", err)
			continue
		}
		raw := record.RawSample
		if len(raw) < 1 {
			continue
		}

		// 先尝试 stack record magic 识别(spec § 4.1.2)。
		// stack record 前 8 字节固定 = STACK_RECORD_MAGIC = 0xDEADBEEFDEADBEEF;
		// base record / sideband 第 1 字节是 kind(0x01 / 0x02),低位远不可能凑成 magic 高位。
		if len(raw) >= 8 {
			magic := binary.LittleEndian.Uint64(raw[:8])
			if magic == StackRecordMagic {
				if len(raw) < int(unsafeSizeofStackEvent()) {
					c.logger.Printf("stack record 长度不足: %d", len(raw))
					continue
				}
				var st bpfStackTraceEvent
				if err := binary.Read(bytes.NewBuffer(raw), binary.LittleEndian, &st); err != nil {
					c.logger.Printf("解析 stack record 失败: %v", err)
					continue
				}
				c.handleStackRecord(&st)
				continue
			}
		}

		kind := raw[0]
		switch kind {
		case evtKindMain:
			var event bpfBinderTraceEvent
			if err := binary.Read(bytes.NewBuffer(raw), binary.LittleEndian, &event); err != nil {
				c.logger.Printf("解析主事件失败: %v", err)
				continue
			}
			c.handleEvent(&event)
		case evtKindSideband:
			var sb bpfBinderSidebandEvent
			if err := binary.Read(bytes.NewBuffer(raw), binary.LittleEndian, &sb); err != nil {
				c.logger.Printf("解析 sideband 事件失败: %v", err)
				continue
			}
			c.handleSideband(&sb)
		case evtKindCookie:
			var cs bpfBinderCookieSideband
			if err := binary.Read(bytes.NewBuffer(raw), binary.LittleEndian, &cs); err != nil {
				c.logger.Printf("解析 cookie sideband 失败: %v", err)
				continue
			}
			c.handleCookieSideband(&cs)
		default:
			c.logger.Printf("未知事件 kind=%d", kind)
		}
	}
}

// unsafeSizeofStackEvent 返回 bpfStackTraceEvent struct size。
// n1 修复:用 unsafe.Sizeof 真实读出 struct size(运行时常量),避免硬编码 544 与
// struct 字段变更时不同步。Go 不允许把 unsafe.Sizeof 在编译期赋给 const,所以仍
// 包成函数,但函数体改为真实 sizeof —— 任何 bpfStackTraceEvent 字段调整自动同步。
func unsafeSizeofStackEvent() uint32 {
	return uint32(unsafe.Sizeof(bpfStackTraceEvent{}))
}

// handleStackRecord 处理 BPF 端送来的 stack_trace_event。
// 同 BPF prog 内 stack 先于 base reserve/submit,daemon 端 stack 必先到。
// 暂存到 pendingStack[transaction_id],TTL 5s(对齐 staleBufferTTL)等 base 合并
// (spec § 4.1.2,nitpicker M2 修正)。
func (c *EventCollector) handleStackRecord(st *bpfStackTraceEvent) {
	c.pendingStackMu.Lock()
	defer c.pendingStackMu.Unlock()
	c.pendingStack[st.TransactionId] = &pendingStackEntry{
		stack:      st,
		receivedNs: c.nowFn().UnixNano(),
	}
	c.evictStalePendingStack()
}

// evictStalePendingStack 清掉超过 TTL 还没等到 base 的孤儿 stack record。
// 调用者必须持 pendingStackMu。
func (c *EventCollector) evictStalePendingStack() {
	if len(c.pendingStack) == 0 {
		return
	}
	cutoff := c.nowFn().UnixNano() - int64(pendingStackTTL)
	for tid, p := range c.pendingStack {
		if p.receivedNs < cutoff {
			delete(c.pendingStack, tid)
			c.stackRecordOrphanCount.Add(1)
		}
	}
}

// sweepLoop 周期性 evict 三个 cookie map 中过期 / LRU 满载的 entry,
// 并把 BPF 端 ringbuf_overflow_map 计数同步到 pairFailureCounters。
func (c *EventCollector) sweepLoop() {
	defer close(c.sweepDone)
	t := time.NewTicker(stateMachineSweepPeriod)
	defer t.Stop()
	for {
		select {
		case <-c.sweepStop:
			return
		case <-t.C:
			c.runSweep(c.nowFn())
			c.syncRingbufOverflow()
			// 顺手扫一下 stack record 孤儿(独立 mutex,不阻塞 stateMu sweep)
			c.pendingStackMu.Lock()
			c.evictStalePendingStack()
			c.pendingStackMu.Unlock()
			// 扫一下 cookie sideband 孤儿(spec daemon-bpf-load-known-issue 通用方案)。
			// M4 修复:pendingCookieByTxid 用独立 pendingCookieMu(避免被 onEvent 阻塞)。
			c.pendingCookieMu.Lock()
			c.evictStalePendingCookies()
			c.pendingCookieMu.Unlock()
		}
	}
}

// perCPUSlotReader 抽象"从 PERCPU map 按槽位读 []uint64"的能力。
// 生产实现走 c.objs.RingbufOverflowMap.Lookup;单测注入 fake 验证 sum 与 slot→counter
// 路由(review M2:不能用 failures.Store 自我验证)。
type perCPUSlotReader func(slot uint32) ([]uint64, error)

// syncRingbufOverflow 把 BPF 端 PERCPU_ARRAY ringbuf_overflow_map 的两个槽汇总到
// failures.ringbufOverflowMain / Sideband(spec § 5.6.3 实现注意)。
func (c *EventCollector) syncRingbufOverflow() {
	if c.objs == nil || c.objs.RingbufOverflowMap == nil {
		return
	}
	c.syncRingbufOverflowFrom(func(slot uint32) ([]uint64, error) {
		var perCPU []uint64
		if err := c.objs.RingbufOverflowMap.Lookup(&slot, &perCPU); err != nil {
			return nil, err
		}
		return perCPU, nil
	})
}

// syncRingbufOverflowFrom 是 syncRingbufOverflow 的纯逻辑版本(单测可注入 reader)。
// slot=0 → ringbuf_overflow_main,slot=1 → ringbuf_overflow_sideband。
// 任一 slot 读失败时跳过该 slot,**不**清掉已有计数(失败可能是临时的)。
func (c *EventCollector) syncRingbufOverflowFrom(reader perCPUSlotReader) {
	for slot := uint32(0); slot < 2; slot++ {
		perCPU, err := reader(slot)
		if err != nil {
			continue
		}
		var total uint64
		for _, v := range perCPU {
			total += v
		}
		switch slot {
		case 0:
			c.failures.ringbufOverflowMain.Store(total)
		case 1:
			c.failures.ringbufOverflowSideband.Store(total)
		}
	}
}

// takePendingStack 摘走对应 transaction_id 的 stack record(若存在)
func (c *EventCollector) takePendingStack(tid uint64) *bpfStackTraceEvent {
	c.pendingStackMu.Lock()
	defer c.pendingStackMu.Unlock()
	p, ok := c.pendingStack[tid]
	if !ok {
		return nil
	}
	delete(c.pendingStack, tid)
	return p.stack
}


// symbolizeStackEvent 把 BPF 抓的 PC 数组反符号化成 StackTrace
func (c *EventCollector) symbolizeStackEvent(st *bpfStackTraceEvent) *StackTrace {
	if st == nil {
		return nil
	}
	out := &StackTrace{}

	// kstack_depth / ustack_depth 都是 -1 → 抓栈失败
	if st.KstackDepth < 0 && st.UstackDepth < 0 {
		out.Quality = StackQualityFailed
		out.FailureReason = "bpf_get_stack returned -1 for both kernel and user"
		return out
	}

	if st.KstackDepth >= 0 {
		for i := 0; i < int(st.KstackDepth) && i < len(st.Kstack); i++ {
			pc := st.Kstack[i]
			if pc == 0 {
				continue
			}
			frame := SymbolFrame{PC: pc, Quality: StackQualityFailed}
			if c.symbolizer != nil {
				frame = c.symbolizer.SymbolizeKernel(pc)
			}
			out.KFrames = append(out.KFrames, StackFrame{
				PC: frame.PC, Module: frame.Module, Symbol: frame.Symbol, Offset: frame.Offset,
			})
		}
		if int(st.KstackDepth) >= 32 {
			out.Truncated |= 0x01
		}
	}
	if st.UstackDepth >= 0 {
		for i := 0; i < int(st.UstackDepth) && i < len(st.Ustack); i++ {
			pc := st.Ustack[i]
			if pc == 0 {
				continue
			}
			frame := SymbolFrame{PC: pc, Quality: StackQualityFailed}
			if c.symbolizer != nil {
				frame = c.symbolizer.SymbolizeUser(int(st.Pid), pc)
			}
			out.UFrames = append(out.UFrames, StackFrame{
				PC: frame.PC, Module: frame.Module, Symbol: frame.Symbol, Offset: frame.Offset,
			})
		}
		if int(st.UstackDepth) >= 32 {
			out.Truncated |= 0x02
		}
	}

	// 综合 quality:任一帧 FAILED 不一定降级整栈,但全 FAILED 才标 FAILED;
	// 否则按"最差非 FULL"标(FP_ONLY > DEGRADED > FULL 优先级)。
	out.Quality = aggregateQuality(out.KFrames, out.UFrames, c.symbolizer)
	return out
}

// aggregateQuality 综合所有帧的 quality 给整栈打总评(spec § 4.4.2)
func aggregateQuality(kf, uf []StackFrame, s *Symbolizer) StackQuality {
	if s == nil {
		return StackQualityDegraded
	}
	// daemon 端在 SymbolizeKernel/User 已经 per-frame 计入 counters,这里给整栈用最朴素的策略:
	// 没有任何帧 → FAILED;只要有一帧,且 user 全部命中 module 但部分缺 symbol → FP_ONLY;否则 FULL。
	allEmpty := len(kf) == 0 && len(uf) == 0
	if allEmpty {
		return StackQualityFailed
	}
	hasFpOnly := false
	hasDegraded := false
	for _, f := range append(append([]StackFrame{}, kf...), uf...) {
		if f.Symbol == "" && f.Module != "" {
			hasFpOnly = true
		}
		if f.Module == "" {
			hasDegraded = true
		}
	}
	if hasDegraded {
		return StackQualityDegraded
	}
	if hasFpOnly {
		return StackQualityFpOnly
	}
	return StackQualityFull
}

// handleEvent 处理单个主事件(合并分片 → 调用栈合并 → 状态机分流)
func (c *EventCollector) handleEvent(event *bpfBinderTraceEvent) {
	c.bufferMu.Lock()
	defer c.bufferMu.Unlock()

	transactionId := event.TransactionId
	nowNs := c.nowFn().UnixNano()

	// 初始化事务缓冲;meta 在第一次见到该 transaction 时定型 (first-seen),
	// 之后到达的分片只补 chunk_data,不覆盖 meta.
	buf, exists := c.transactionBuffers[transactionId]
	if !exists {
		totalChunks := (event.DataSize + ChunkSize - 1) / ChunkSize
		buf = &txBuffer{
			firstSeenNs: nowNs,
			chunks:      make([][]byte, totalChunks),
			meta: txMeta{
				pid:        event.Pid,
				uid:        event.Uid,
				code:       event.Code,
				flags:      event.Flags,
				dataSize:   event.DataSize,
				isReply:    event.IsReply,
				binderDev:  event.BinderDev,
				targetKind: event.TargetKind,
				toPid:      event.ToPid,
				toUid:      event.ToUid,
				targetRef:  event.TargetRef,
				pairId:     event.PairId,
				cookie:     event.Cookie,
			},
		}
		c.transactionBuffers[transactionId] = buf

		// spec daemon-bpf-load-known-issue 通用方案:
		// 主 program 不再写 cookie 字段(event.Cookie == 0),cookie 由旁路 cookie kprobe
		// 通过 EVT_KIND_COOKIE sideband 上送。如果 cookie sideband 已先到、暂存在
		// pendingCookieByTxid[transactionId],立即注入 buf.meta.cookie。
		// 旧路径(event.Cookie != 0,例如单测直接构造的事件)优先级更高,不覆盖。
		//
		// M4 修复:pendingCookieByTxid 走独立 pendingCookieMu。锁顺序 bufferMu →
		// pendingCookieMu 单向。
		if buf.meta.cookie == 0 {
			c.pendingCookieMu.Lock()
			if pe, ok := c.pendingCookieByTxid[transactionId]; ok {
				buf.meta.cookie = pe.cookie
				if pe.listElem != nil {
					c.pendingCookieLRU.Remove(pe.listElem)
				}
				delete(c.pendingCookieByTxid, transactionId)
			}
			c.pendingCookieMu.Unlock()
		}
	}

	// 存储分片
	if int(event.ChunkIndex) < len(buf.chunks) {
		buf.chunks[event.ChunkIndex] = append([]byte(nil), event.ChunkData[:]...)
	}

	// 检查是否所有分片都已接收
	complete := true
	for _, chunk := range buf.chunks {
		if chunk == nil {
			complete = false
			break
		}
	}

	if complete {
		// 合并所有分片
		completeData := c.mergeChunks(buf.chunks)
		meta := buf.meta
		delete(c.transactionBuffers, transactionId)

		// 验证数据
		if len(completeData) <= 16 || len(completeData) > ChunkSize*MaxChunks {
			c.maybeCleanup(nowNs)
			return
		}

		// 防御 DataSize=0 / 越界
		if meta.dataSize == 0 || int(meta.dataSize) > len(completeData) {
			c.logger.Printf("handleEvent: DataSize 不合法, 丢弃事件 tid=%d dataSize=%d actualLen=%d",
				transactionId, meta.dataSize, len(completeData))
			c.maybeCleanup(nowNs)
			return
		}

		parcelData := completeData[:meta.dataSize]

		mainEvent := &BinderEventPayload{
			Timestamp:  nowNs,
			Pid:        meta.pid,
			Uid:        meta.uid,
			Code:       meta.code,
			Flags:      meta.flags,
			DataSize:   uint32(len(parcelData)),
			IsReply:    meta.isReply,
			BinderDev:  meta.binderDev,
			TargetKind: meta.targetKind,
			ToPid:      meta.toPid,
			ToUid:      meta.toUid,
			TargetRef:  meta.targetRef,
			PairId:     meta.pairId,
			ParcelData: parcelData,
		}

		// 合并 pendingStack(spec § 4.1.2 + § 13 D3 checklist):
		// stack record 先到 → 反符号化挂上事件;否则 stackTrace=nil,事件正常上送
		if st := c.takePendingStack(transactionId); st != nil {
			mainEvent.StackTrace = c.symbolizeStackEvent(st)
		} else {
			c.baseRecordNoStackCount.Add(1)
		}

		// 状态机分流(reply / SYNC / ONE_WAY)。bufferMu 不要拿着进 stateMu,先释放。
		// (注意:此处 defer 已经持有 bufferMu;为简化只在 stateMu 里操作 cookie maps)
		c.routeMainEvent(mainEvent, meta.cookie, transactionId, nowNs)
	}

	c.maybeCleanup(nowNs)
}

// routeMainEvent 主事件状态机入口(spec § 5.6.3 主记录到达表)。
//
//	reply           → 已经在 BPF 端写好 PairId(thread->transaction_stack->debug_id),直接 emit。
//	cookie==0 SYNC  → 挂到 pendingMainByTxid[transactionId] 等 cookie sideband 反向激活,
//	                  spec 2026-05-09 daemon-cookie-race-fix。5s TTL 兜底走原降级路径。
//	cookie==0 ONEWAY → 沿用旧 cookie==0 直接 emit 路径(ONEWAY 没有 reply 配对需求)。
//	cookie!=0 SYNC  → 写入 pendingMain[cookie],等 sideband 或 5s TTL。
//	ONE_WAY         → lookup pendingOneway[cookie]:命中合并 emit;未命中 cookie-only emit。
//	                  无论命中与否,都把 cookie 写入 recentOnewayEmit(1s TTL,识别晚到 sideband)。
func (c *EventCollector) routeMainEvent(ev *BinderEventPayload, cookie uint64, transactionId uint64, nowNs int64) {
	if ev.IsReply != 0 {
		c.emit(ev)
		return
	}
	isOnewayPre := (ev.Flags & txFlagOneWay) != 0
	if cookie == 0 {
		// spec 2026-05-09 daemon-cookie-race-fix:cookie==0 SYNC 挂 pendingMainByTxid
		// 等 cookie sideband 反向激活(微秒级窗口)。ONEWAY 不挂,沿用旧"直接 emit"
		// 语义(ONEWAY 没有 reply,无配对需求)。
		// 测试钩子 legacyCookieZeroSyncEmit:存量测试用例继续走旧"直接 emit"语义。
		if isOnewayPre || transactionId == 0 || c.legacyCookieZeroSyncEmit {
			c.emit(ev)
			return
		}
		var toEmit []*BinderEventPayload
		c.stateMu.Lock()
		// nitpicker B2:同 transaction_id 复用(BPF 重入或 chunks 提前 emit 后再来同 id)
		// 时,旧 entry 必须先降级 emit(pair_id=0)再被覆盖,否则 listElem 残留 LRU 导致
		// map/list 失同步、事件静默丢失。
		if old, ok := c.pendingMainByTxid[transactionId]; ok {
			c.pendingMainTxidLRU.Remove(old.listElem)
			delete(c.pendingMainByTxid, transactionId)
			c.failures.entryEarlyReturn.Add(1)
			toEmit = append(toEmit, old.event)
		}
		pe := &pendingMainEntry{enqueuedNs: nowNs, event: ev}
		pe.listElem = c.pendingMainTxidLRU.PushFront(transactionId)
		c.pendingMainByTxid[transactionId] = pe
		toEmit = append(toEmit, c.evictLRUIfFullCollect(c.pendingMainTxidLRU,
			pendingMainCapacity, c.evictPendingMainTxidBackCollect)...)
		c.stateMu.Unlock()
		c.flushEmissions(toEmit)
		return
	}

	// review M1:**严禁**在 stateMu 内同步调 onEvent —— 客户端慢/断会回压整条 sideband 路径。
	// 收集本轮要 emit 的事件到 pending slice,出锁后再批量 flush。
	var toEmit []*BinderEventPayload
	isOneway := isOnewayPre

	c.stateMu.Lock()
	// cookie 重复检测(替代原"乱序到达"check,见 review B1)。
	c.checkCookieDuplicate(cookie)

	switch {
	case isOneway:
		// 先 lookup pendingOneway:sideband 早到 → 合并 emit
		if pe, ok := c.pendingOneway[cookie]; ok {
			c.applySideband(ev, pe.kernelDebugId, pe.toPid, pe.toUid, pe.toUidUnsupported)
			c.pendingOnewayLRU.Remove(pe.listElem)
			delete(c.pendingOneway, cookie)
		}
		// 不论 sideband 是否早到,oneway 主都立即 emit + 标 recentOnewayEmit
		c.recordRecentOnewayEmit(cookie, nowNs)
		toEmit = append(toEmit, ev)
	default:
		// SYNC:写 pendingMain,等 sideband 或 5s TTL evict;LRU 满时 evictBack 通过
		// pendingEmissions 队列收集要 emit 的事件,统一在锁外 flush
		pe := &pendingMainEntry{
			enqueuedNs: nowNs,
			event:      ev,
		}
		pe.listElem = c.pendingMainLRU.PushFront(cookie)
		c.pendingMain[cookie] = pe
		evicted := c.evictLRUIfFullCollect(c.pendingMainLRU, pendingMainCapacity, c.evictPendingMainBackCollect)
		toEmit = append(toEmit, evicted...)
	}
	c.stateMu.Unlock()

	c.flushEmissions(toEmit)
}

// checkCookieDuplicate 是 cookie 重复检测的 sanity 实现(stateMu 持有期间调用)。
// 维护最近 recentCookieRingSize 条 cookie 的环形 set:命中 = 重复 → +1 violation;
// miss = 入 set + 推环,踢出最旧条目。
//
// 不在乎 cookie 到达顺序 —— per-CPU ringbuf 出队天然乱序,只要"同一 cookie 在窗口
// 内出现两次"就报警(BPF 端 __sync_fetch_and_add 全局原子分配,正常不可能复用)。
func (c *EventCollector) checkCookieDuplicate(cookie uint64) {
	if _, dup := c.recentCookieSet[cookie]; dup {
		c.failures.cookieMonotonicViolation.Add(1)
		return
	}
	// 踢出最旧条目(若环已写过一圈;0 是初始空 slot,跳过)
	old := c.recentCookieRing[c.recentCookieHead]
	if old != 0 {
		delete(c.recentCookieSet, old)
	}
	c.recentCookieRing[c.recentCookieHead] = cookie
	c.recentCookieHead = (c.recentCookieHead + 1) % len(c.recentCookieRing)
	c.recentCookieSet[cookie] = struct{}{}
}

// recordRecentOnewayEmit 在 oneway 主 emit 后记录 cookie,1s TTL 内可识别 sideband 晚到。
func (c *EventCollector) recordRecentOnewayEmit(cookie uint64, nowNs int64) {
	if old, ok := c.recentOneway[cookie]; ok {
		c.recentOnewayLRU.MoveToFront(old.listElem)
		old.enqueuedNs = nowNs
		return
	}
	re := &recentOnewayEntry{enqueuedNs: nowNs}
	re.listElem = c.recentOnewayLRU.PushFront(cookie)
	c.recentOneway[cookie] = re
	// recentOnewayEmit LRU evict 无副作用(只清 map),不会产生 emit。
	c.evictLRUIfFullCollect(c.recentOnewayLRU, recentOnewayCapacity,
		func(cookie uint64) []*BinderEventPayload {
			delete(c.recentOneway, cookie)
			return nil
		})
}

// handleSideband 处理 raw_tp 上送的 sideband 事件(spec § 5.6.3 sideband 到达表)
//
// review M1:onEvent 的同步调用必须放到锁外,避免客户端慢/断时 SocketServer 阻塞
// 把整条 sideband 路径冻住。本函数在 stateMu 内只更新 map / counter,emit 队列
// flush 出锁后做。
func (c *EventCollector) handleSideband(sb *bpfBinderSidebandEvent) {
	nowNs := c.nowFn().UnixNano()
	cookie := sb.Cookie

	var toEmit *BinderEventPayload

	c.stateMu.Lock()
	switch {
	case c.pendingMain[cookie] != nil:
		// lookup 1: pendingMain(SYNC 主在等)→ 合并 emit
		pm := c.pendingMain[cookie]
		c.applySideband(pm.event, sb.KernelDebugId, sb.ToPid, sb.ToUid, sb.ToUidUnsupported)
		c.pendingMainLRU.Remove(pm.listElem)
		delete(c.pendingMain, cookie)
		toEmit = pm.event
	case c.pendingOneway[cookie] != nil:
		// lookup 2: pendingOneway —— 重复 sideband(BPF 重发 bug)。
		// 清掉 pendingOneway[cookie] 防止后续 sweeper TTL 二次累加 sideband_orphan
		// (review B2:同一 BPF bug 计 2 次会破坏 spec § 6.2 不变量
		// `sideband_orphan ≤ ringbuf_overflow_main`)。
		e := c.pendingOneway[cookie]
		c.pendingOnewayLRU.Remove(e.listElem)
		delete(c.pendingOneway, cookie)
		c.failures.sidebandOrphan.Add(1)
	case c.recentOneway[cookie] != nil:
		// lookup 3: recentOnewayEmit —— oneway 主已 emit,1s 内晚到的 sideband 是已知噪声
		c.failures.onewaySidebandLate.Add(1)
	default:
		// 兜底:写 pendingOneway 等 1s,TTL 到期仍无主 → sideband_orphan
		pe := &pendingOnewayEntry{
			enqueuedNs:       nowNs,
			kernelDebugId:    sb.KernelDebugId,
			toPid:            sb.ToPid,
			toUid:            sb.ToUid,
			toUidUnsupported: sb.ToUidUnsupported,
		}
		pe.listElem = c.pendingOnewayLRU.PushFront(cookie)
		c.pendingOneway[cookie] = pe
		// pendingOneway evict 不会产生 emit(只 +1 sideband_orphan),
		// 这里的 collect 形式仍然走得通,evicted slice 总是空。
		c.evictLRUIfFullCollect(c.pendingOnewayLRU, pendingOnewayCapacity, func(cookie uint64) []*BinderEventPayload {
			c.evictPendingOnewayBack(cookie)
			return nil
		})
	}
	c.stateMu.Unlock()

	if toEmit != nil {
		c.flushEmissions([]*BinderEventPayload{toEmit})
	}
}

// handleCookieSideband 处理 cookie 旁路 kprobe 上送的 cookie sideband(spec
// daemon-bpf-load-known-issue 通用方案)。
//
// 关联键 = transaction_id_hint(主 program 通过 BPF tid_to_txid_map 写入,
// cookie program 读出后填入 sideband)。两条路径:
//
//	a) 主事件已到、buf.meta.cookie==0 → 直接注入 buf.meta.cookie
//	b) 主事件未到 → 写 pendingCookieByTxid[transaction_id_hint] 等主事件第一次见到时合并
//
// transaction_id_hint == 0 表示 BPF tid_to_txid_map lookup miss(罕见,见
// cookie_kprobe_binder_transaction.byte.c 注释),此时无关联键,沉默丢弃 cookie
// sideband(daemon 视主事件为降级路径,raw_tp 后续 sideband 走 sideband_orphan
// 兜底,与 spec § 5.6.3 既有路径自然吻合)。
func (c *EventCollector) handleCookieSideband(cs *bpfBinderCookieSideband) {
	if cs.TransactionIdHint == 0 || cs.Cookie == 0 {
		return
	}
	transactionId := cs.TransactionIdHint
	cookie := cs.Cookie

	// case a:主事件已到、chunks 还在收集 → 直接注入 buf.meta.cookie(走 bufferMu)。
	c.bufferMu.Lock()
	if buf, ok := c.transactionBuffers[transactionId]; ok && buf.meta.cookie == 0 {
		buf.meta.cookie = cookie
		c.bufferMu.Unlock()
		return
	}
	c.bufferMu.Unlock()

	// spec 2026-05-09 daemon-cookie-race-fix:case a' 反向激活 — 主事件已 emit 出
	// chunks 收集器、走 cookie==0 路径暂存在 pendingMainByTxid。把它转挂到
	// pendingMain[cookie] 等 raw_tp sideband 来注入 pair_id;cookie 由 cookie program
	// 与 raw_tp 共用 BPF pending_map[tid] 写入,raw_tp sideband 与本 cookie sideband
	// 携带的 cookie 是同一个值,转挂后自然对得上。
	c.stateMu.Lock()
	if pe, ok := c.pendingMainByTxid[transactionId]; ok {
		c.pendingMainTxidLRU.Remove(pe.listElem)
		delete(c.pendingMainByTxid, transactionId)
		var toEmit []*BinderEventPayload
		// nitpicker B1:转挂前 pendingMain[cookie] 若已存在(同 cookie 旧 entry,
		// 罕见但可能由 BPF cookie program 重发或主事件 cookie!=0 路径已挂触发),
		// 旧 entry 必须先降级 emit(pair_id=0)再被覆盖,避免 listElem 残留 LRU。
		if oldPe, exists := c.pendingMain[cookie]; exists {
			c.pendingMainLRU.Remove(oldPe.listElem)
			delete(c.pendingMain, cookie)
			c.failures.entryEarlyReturn.Add(1)
			toEmit = append(toEmit, oldPe.event)
		}
		// 转挂 pendingMain[cookie] 等 raw_tp sideband;cookie 写到 entry 自身字段
		// 用于后续 evict 路径不必再绕 list.Element.Value。
		pe.cookie = cookie
		pe.listElem = c.pendingMainLRU.PushFront(cookie)
		c.pendingMain[cookie] = pe
		toEmit = append(toEmit, c.evictLRUIfFullCollect(c.pendingMainLRU,
			pendingMainCapacity, c.evictPendingMainBackCollect)...)
		c.stateMu.Unlock()
		c.flushEmissions(toEmit)
		return
	}
	c.stateMu.Unlock()

	// case b:主事件未到。bufferMu 已释放,改 pendingCookieMu。
	c.pendingCookieMu.Lock()
	defer c.pendingCookieMu.Unlock()

	// case b:主事件未到,暂存等合并(M3 修复:维护 LRU + 容量上限)。
	if old, ok := c.pendingCookieByTxid[transactionId]; ok {
		// 同 transaction_id 重复 cookie sideband(BPF 重发):move-to-front,
		// 更新 cookie 与 receivedNs(后到的覆盖)。
		c.pendingCookieLRU.MoveToFront(old.listElem)
		old.cookie = cookie
		old.receivedNs = c.nowFn().UnixNano()
	} else {
		pe := &pendingCookieEntry{
			cookie:     cookie,
			receivedNs: c.nowFn().UnixNano(),
		}
		pe.listElem = c.pendingCookieLRU.PushFront(transactionId)
		c.pendingCookieByTxid[transactionId] = pe
	}
	// 容量超限 → 从尾部 evict 最旧 entry(无副作用,与 evictStalePendingCookies 路径
	// 同语义:cookie 主未到,沉默吸收,不计 EntryEarlyReturn / SidebandOrphan)。
	for c.pendingCookieLRU.Len() > pendingCookieCapacity {
		back := c.pendingCookieLRU.Back()
		if back == nil {
			break
		}
		txid, ok := back.Value.(uint64)
		c.pendingCookieLRU.Remove(back)
		if ok {
			delete(c.pendingCookieByTxid, txid)
		}
	}
	c.evictStalePendingCookies()
}

// evictStalePendingCookies 清掉超过 pendingCookieTTL 还没等到主事件的孤儿 cookie。
// 调用者必须持 pendingCookieMu(M4 修复:从 bufferMu 迁出)。
// 同步移除 pendingCookieLRU 链表节点(M3 修复)。
func (c *EventCollector) evictStalePendingCookies() {
	if len(c.pendingCookieByTxid) == 0 {
		return
	}
	cutoff := c.nowFn().UnixNano() - int64(pendingCookieTTL)
	for txid, p := range c.pendingCookieByTxid {
		if p.receivedNs < cutoff {
			if p.listElem != nil {
				c.pendingCookieLRU.Remove(p.listElem)
			}
			delete(c.pendingCookieByTxid, txid)
			// 没有专门的 telemetry bucket(主事件已被 entry kprobe early return 过滤掉,
			// 等价 entry_early_return —— 但这里 cookie kprobe 已分配并 emit cookie,
			// raw_tp 后续上送的 sideband 会走 sideband_orphan 兜底,等等价。
			// 不在这里 +1 entry_early_return,避免双计)。
		}
	}
}

// applySideband 把 sideband 字段拷到主事件 payload(只覆盖 BPF 端 stub-0 字段)
func (c *EventCollector) applySideband(ev *BinderEventPayload, debugID uint64, toPid, toUid uint32, toUidUnsupported uint8) {
	if ev.PairId == 0 && debugID != 0 {
		ev.PairId = debugID
	}
	if ev.ToPid == 0 && toPid != 0 {
		ev.ToPid = toPid
	}
	if toUidUnsupported == 0 && toUid != 0 && ev.ToUid == 0 {
		ev.ToUid = toUid
	}
	// to_uid_unsupported=1 时 ToUid 保留 0,app 端 ConfidenceMarker 不会在 to_uid 命中上做判断
	// (spec § 5.6.5:server 方向 reply 仍 orphan,等 13+ 设备生效)。
}

// emit 锁外直接发(reply 主事件 / cookie==0 SYNC 降级路径走这里;
// 状态机内部统一用 flushEmissions,确保锁外 emit)。
func (c *EventCollector) emit(ev *BinderEventPayload) {
	if c.onEvent != nil {
		c.onEvent(ev)
	}
}

// runSweep TTL/LRU 后台扫描,用 nowFn 拿当前时间(测试可注入)。
// 仅按 enqueuedNs + TTL 判断过期,不再做复杂排序。
//
// review M1:emit 必须出锁后做。pendingMain TTL evict 会 emit pair_id=0 主事件,
// 这里把它们收集到本地 slice,锁释放后批量 flush。
func (c *EventCollector) runSweep(now time.Time) {
	cutoffMain := now.Add(-pendingMainTTL).UnixNano()
	cutoffOneway := now.Add(-pendingOnewayTTL).UnixNano()
	cutoffRecent := now.Add(-recentOnewayEmitTTL).UnixNano()

	var toEmit []*BinderEventPayload

	c.stateMu.Lock()
	// pendingMain TTL → entry_early_return + emit pair_id=0
	for cookie, pm := range c.pendingMain {
		if pm.enqueuedNs < cutoffMain {
			c.pendingMainLRU.Remove(pm.listElem)
			delete(c.pendingMain, cookie)
			c.failures.entryEarlyReturn.Add(1)
			toEmit = append(toEmit, pm.event)
		}
	}
	// spec 2026-05-09 daemon-cookie-race-fix:pendingMainByTxid 同 TTL → 降级 emit
	// pair_id=0,等价改造前 cookie==0 直接 emit 行为。
	for txid, pm := range c.pendingMainByTxid {
		if pm.enqueuedNs < cutoffMain {
			c.pendingMainTxidLRU.Remove(pm.listElem)
			delete(c.pendingMainByTxid, txid)
			c.failures.entryEarlyReturn.Add(1)
			toEmit = append(toEmit, pm.event)
		}
	}
	// pendingOneway TTL → sideband_orphan(spec § 5.6.3)
	for cookie, pe := range c.pendingOneway {
		if pe.enqueuedNs < cutoffOneway {
			c.pendingOnewayLRU.Remove(pe.listElem)
			delete(c.pendingOneway, cookie)
			c.failures.sidebandOrphan.Add(1)
		}
	}
	// recentOnewayEmit TTL → 无副作用
	for cookie, re := range c.recentOneway {
		if re.enqueuedNs < cutoffRecent {
			c.recentOnewayLRU.Remove(re.listElem)
			delete(c.recentOneway, cookie)
		}
	}
	c.stateMu.Unlock()

	c.flushEmissions(toEmit)
}

// evictLRUIfFullCollect 当 LRU 容量超过 cap 时,从 list 末尾(最旧)逐一 evict
// 调用 evictBack;evictBack 可返回需要在 stateMu 释放后 emit 的事件,本函数把它们
// 拼接到返回 slice(review M1:严禁锁内同步 emit)。
func (c *EventCollector) evictLRUIfFullCollect(l *list.List, cap int,
	evictBack func(cookie uint64) []*BinderEventPayload) []*BinderEventPayload {
	var emits []*BinderEventPayload
	for l.Len() > cap {
		back := l.Back()
		if back == nil {
			return emits
		}
		cookie, ok := back.Value.(uint64)
		if !ok {
			l.Remove(back)
			continue
		}
		l.Remove(back)
		emits = append(emits, evictBack(cookie)...)
	}
	return emits
}

// evictPendingMainBackCollect 把 pendingMain LRU 末尾 entry 删掉并返回它的 event 供
// 调用方在锁外 emit(pair_id=0,落 entry_early_return)。
func (c *EventCollector) evictPendingMainBackCollect(cookie uint64) []*BinderEventPayload {
	pm, ok := c.pendingMain[cookie]
	if !ok {
		return nil
	}
	delete(c.pendingMain, cookie)
	c.failures.entryEarlyReturn.Add(1)
	return []*BinderEventPayload{pm.event}
}

// evictPendingMainTxidBackCollect 同 evictPendingMainBackCollect,作用于
// pendingMainByTxid(spec 2026-05-09 daemon-cookie-race-fix)。LRU 满时降级 emit
// pair_id=0 等价改造前 cookie==0 直接 emit 行为。调用者持有 stateMu。
func (c *EventCollector) evictPendingMainTxidBackCollect(transactionId uint64) []*BinderEventPayload {
	pm, ok := c.pendingMainByTxid[transactionId]
	if !ok {
		return nil
	}
	delete(c.pendingMainByTxid, transactionId)
	c.failures.entryEarlyReturn.Add(1)
	return []*BinderEventPayload{pm.event}
}

func (c *EventCollector) evictPendingOnewayBack(cookie uint64) {
	if _, ok := c.pendingOneway[cookie]; !ok {
		return
	}
	delete(c.pendingOneway, cookie)
	c.failures.sidebandOrphan.Add(1)
}

// flushEmissions 在 stateMu 释放后批量 emit。onEvent 走 SocketServer 异步通道,
// 但仍可能阻塞(客户端断开 / 缓冲满),所以**必须**在锁外调用。
func (c *EventCollector) flushEmissions(events []*BinderEventPayload) {
	if len(events) == 0 || c.onEvent == nil {
		return
	}
	for _, ev := range events {
		c.onEvent(ev)
	}
}

// mergeChunks 合并分片
func (c *EventCollector) mergeChunks(chunks [][]byte) []byte {
	mergedData := make([]byte, 0, len(chunks)*ChunkSize)
	for _, chunk := range chunks {
		mergedData = append(mergedData, chunk...)
	}
	return mergedData
}

// maybeCleanup 周期性触发过期事务清理(每 cleanupEveryNEvts 次事件触发一次)
func (c *EventCollector) maybeCleanup(nowNs int64) {
	c.eventCounter++
	if c.eventCounter%cleanupEveryNEvts != 0 {
		return
	}
	cutoff := nowNs - int64(staleBufferTTL)
	removed := 0
	for id, buf := range c.transactionBuffers {
		if buf.firstSeenNs < cutoff {
			delete(c.transactionBuffers, id)
			removed++
		}
	}
	if removed > 0 {
		c.logger.Printf("cleanup: 移除了 %d 个超过 %v 仍未集齐的事务缓冲, 当前缓冲数=%d",
			removed, staleBufferTTL, len(c.transactionBuffers))
	}
	// pendingStack 100ms 失效:孤儿 stack record(理论上 base 总会到达,孤儿极少)
	c.pendingStackMu.Lock()
	c.evictStalePendingStack()
	c.pendingStackMu.Unlock()

	// pendingCookieByTxid 5s 失效:孤儿 cookie sideband(主事件 early-return 时派生)。
	// M4 修复:pendingCookieByTxid 用独立 pendingCookieMu,这里 acquire 再 release。
	c.pendingCookieMu.Lock()
	c.evictStalePendingCookies()
	c.pendingCookieMu.Unlock()
}

// FailureSnapshot 把 5 个失败 bucket + sanity check + 已知噪声指标导出
// (供运维 SocketServer 周期性 push 给 app 或日志)。
type FailureSnapshot struct {
	EntryEarlyReturn         uint64
	SidebandOrphan           uint64
	RingbufOverflowMain      uint64
	RingbufOverflowSideband  uint64
	CookieMonotonicViolation uint64
	OnewaySidebandLate       uint64
}

func (c *EventCollector) FailureSnapshot() FailureSnapshot {
	return FailureSnapshot{
		EntryEarlyReturn:         c.failures.entryEarlyReturn.Load(),
		SidebandOrphan:           c.failures.sidebandOrphan.Load(),
		RingbufOverflowMain:      c.failures.ringbufOverflowMain.Load(),
		RingbufOverflowSideband:  c.failures.ringbufOverflowSideband.Load(),
		CookieMonotonicViolation: c.failures.cookieMonotonicViolation.Load(),
		OnewaySidebandLate:       c.failures.onewaySidebandLate.Load(),
	}
}
