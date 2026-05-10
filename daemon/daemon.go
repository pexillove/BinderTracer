package main

import (
	"fmt"
	"log"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
)

// Daemon btrace守护进程
type Daemon struct {
	logger     *log.Logger
	client     *SocketClient
	collector  *EventCollector
	symbolizer *Symbolizer

	// 配置
	listenAddr   string
	targetUid    uint32
	pidFile      string
	sessionToken string

	// 状态
	running      bool
	shutdownOnce sync.Once
}

// DaemonConfig 守护进程配置
type DaemonConfig struct {
	ListenAddr   string
	TargetUid    uint32
	LogFile      string
	PidFile      string // 空字符串表示不启用单实例保护
	SessionToken string // 空字符串表示不启用会话校验
}

// NewDaemon 创建守护进程
func NewDaemon(config DaemonConfig) (*Daemon, error) {
	// 初始化日志
	var logger *log.Logger
	if config.LogFile != "" {
		file, err := os.OpenFile(config.LogFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
		if err != nil {
			return nil, err
		}
		logger = log.New(file, "[btrace-daemon] ", log.Ldate|log.Ltime|log.Lmicroseconds)
	} else {
		logger = log.New(os.Stdout, "[btrace-daemon] ", log.Ldate|log.Ltime|log.Lmicroseconds)
	}

	listenAddr := config.ListenAddr
	if listenAddr == "" {
		listenAddr = DefaultListenAddr
	}

	daemon := &Daemon{
		logger:       logger,
		listenAddr:   listenAddr,
		targetUid:    config.TargetUid,
		pidFile:      config.PidFile,
		sessionToken: config.SessionToken,
		running:      false,
	}

	return daemon, nil
}

// acquireSingleton 检查 PID 文件;若旧实例仍活跃则拒绝启动,否则写入自己的 PID。
//
// spec § 8.3:宁可拒绝启动,也不允许两个 daemon 同时挂 BPF 探针 ——
// 否则两个实例会向同一个 App TCP server 发事件,App 看到双倍流量且无法分辨。
func (d *Daemon) acquireSingleton() error {
	if d.pidFile == "" {
		d.logger.Println("acquireSingleton: 未配置 PID 文件,跳过单实例锁")
		return nil
	}

	if data, err := os.ReadFile(d.pidFile); err == nil {
		if oldPid, perr := strconv.Atoi(strings.TrimSpace(string(data))); perr == nil && oldPid > 1 {
			if isProcessAlive(oldPid) {
				return fmt.Errorf("已有旧实例存活: pid=%d (PID 文件=%s),拒绝启动", oldPid, d.pidFile)
			}
			d.logger.Printf("acquireSingleton: 发现失效 PID=%d,清理后继续", oldPid)
		}
	}

	pid := os.Getpid()
	// 必须带尾换行!libsu 的 shell 把 stdout/stderr 按行收集,如果文件内容没有 \n,
	// `cat pid` 的输出不会被切成一行 → App 侧 parsePids 拿到空集合 → 误判 daemon 启动失败
	// → 关掉 server → daemon retry connect 失败 → 自杀,死循环。
	if err := os.WriteFile(d.pidFile, []byte(strconv.Itoa(pid)+"\n"), 0644); err != nil {
		return fmt.Errorf("写 PID 文件 %s 失败: %w", d.pidFile, err)
	}
	d.logger.Printf("acquireSingleton: 已写入 PID 文件 %s (pid=%d)", d.pidFile, pid)
	return nil
}

// releasePidFile 删除属于本进程的 PID 文件。spec § 8.2:daemon 自己负责清理。
//
// 严格只删 "PID 文件内容 == 自己 PID" 的情况,避免在异常重叠场景下误删别人的锁。
func (d *Daemon) releasePidFile() {
	if d.pidFile == "" {
		return
	}
	data, err := os.ReadFile(d.pidFile)
	if err != nil {
		return
	}
	owner, perr := strconv.Atoi(strings.TrimSpace(string(data)))
	if perr != nil || owner != os.Getpid() {
		d.logger.Printf("releasePidFile: PID 文件归属不是自己 (owner=%d, self=%d),跳过删除", owner, os.Getpid())
		return
	}
	if err := os.Remove(d.pidFile); err != nil {
		d.logger.Printf("releasePidFile: 删除 PID 文件失败: %v", err)
	} else {
		d.logger.Printf("releasePidFile: 已删除 PID 文件 %s", d.pidFile)
	}
}

// probeBTFAvailability 检查内核 BTF 信息是否可用。
//
// CO-RE BPF 程序(本项目通过 vmlinux.h + BPF_CORE_READ 实现)在加载到内核时,
// 由 libbpf 读取 /sys/kernel/btf/vmlinux,把编译期记录的字段访问按设备本地内核 layout
// 重定位。如果 BTF 不存在,加载阶段就会拒绝;启动前 warn 一下方便运维定位问题。
//
// 不主动 fallback —— 当前 daemon 没有 v1 / v2 BPF 程序双轨,加载失败本身就是 fatal,
// 由上游(daemon Run() 报错退出 + app 端识别 daemon 死亡 + 用户看到错误)自然处理。
func (d *Daemon) probeBTFAvailability() {
	const btfPath = "/sys/kernel/btf/vmlinux"
	info, err := os.Stat(btfPath)
	if err != nil {
		d.logger.Printf("probeBTFAvailability: 无法访问 %s (%v) —— BPF CO-RE 加载预计失败,daemon 将无法启动", btfPath, err)
		return
	}
	// 只 stat 不读取内容(几 MB,没必要全读),Mode().IsRegular 校验是文件而不是目录链接异常。
	if !info.Mode().IsRegular() {
		d.logger.Printf("probeBTFAvailability: %s 不是普通文件 (mode=%v),BPF CO-RE 可能加载失败", btfPath, info.Mode())
		return
	}
	d.logger.Printf("probeBTFAvailability: %s 可用 (size=%d bytes),CO-RE 重定位将正常工作", btfPath, info.Size())
}

// isProcessAlive 用 kill -0 / signal 0 探测目标 PID 是否还活着。
//
// Linux 上 signal 0 不发任何信号,只走权限/存活校验:进程不存在返回 ESRCH,
// 存在(即便没权限发信号)返回 nil 或 EPERM —— 两种都说明进程在。
func isProcessAlive(pid int) bool {
	if pid <= 1 {
		return false
	}
	process, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	err = process.Signal(syscall.Signal(0))
	if err == nil {
		return true
	}
	// EPERM 也代表进程存在(只是没权限发)
	return err.Error() == "operation not permitted"
}

// Run 运行守护进程
func (d *Daemon) Run() error {
	d.logger.Println("btrace守护进程启动中...")

	// spec § 8.3:在做任何 BPF / TCP 工作前先抢单实例锁。失败直接退出,
	// 不进入 collector / client 初始化,避免半启动残留。
	if err := d.acquireSingleton(); err != nil {
		return err
	}
	// 任何启动失败路径(后面 collector/client 出错)都要释放 PID 文件,否则下次 daemon
	// 启动会被自己之前的残留挡住。shutdown 路径也会调一次,releasePidFile 内部
	// 通过比对 owner 自防重复删 → 双调用安全。
	defer d.releasePidFile()

	// spec § 8.3:BTF 可用性预探测。BPF 程序已在编译期通过 vmlinux.h 嵌入 CO-RE 重定位
	// 记录,运行时由内核 libbpf 对照设备本地 /sys/kernel/btf/vmlinux 修正字段偏移。
	// 如果 BTF 不存在或不可读,libbpf 在 loadBpfObjects 阶段会失败 —— 这里只做提前 warn,
	// 让运维日志能区分 "BTF 缺失" vs "其它 BPF 加载错误";不主动切换 v1 协议。
	d.probeBTFAvailability()

	// 创建事件收集器
	d.collector = NewEventCollector(d.logger)

	// spec 2026-05-03 § 4.3:启动期一次性加载 kallsyms。失败不致命,
	// daemon 仍可工作(只是内核栈回落到 raw PC)。
	d.symbolizer = NewSymbolizer(d.logger)
	if err := d.symbolizer.LoadKallsyms("/proc/kallsyms"); err != nil {
		d.logger.Printf("kallsyms 加载失败,内核栈反符号化降级: %v", err)
	}
	d.collector.SetSymbolizer(d.symbolizer)

	// 创建TCP客户端（连接App)。把 sessionToken 透给 client,
	// daemon 在 ACK 里把它回传,App 端校验确认是新实例。
	d.client = NewSocketClient(d.listenAddr, d.logger)
	d.client.SetSessionToken(d.sessionToken)

	// 设置事件回调：将eBPF事件转发给Socket客户端
	d.collector.SetEventCallback(func(event *BinderEventPayload) {
		d.client.SendEvent(event)
	})

	// 设置命令处理回调
	d.client.SetHandlers(
		d.handleSetTarget,
		d.handlePause,
		d.handleResume,
		d.handleShutdown,
	)
	d.client.SetDisconnectHandler(d.handleDisconnect)

	// 启动事件收集器
	if err := d.collector.Start(); err != nil {
		return err
	}

	// 设置初始目标UID（优先使用命令行参数）
	if d.targetUid > 0 {
		if err := d.collector.SetTargetUid(d.targetUid); err != nil {
			d.collector.Stop()
			return err
		}
	}

	d.running = true
	// 连接App Server（失败3次直接退出）
	if err := d.client.Connect(); err != nil {
		d.running = false
		d.collector.Stop()
		return err
	}
	d.logger.Println("btrace守护进程已启动")
	d.logger.Printf("连接地址: %s", d.listenAddr)

	// 等待信号
	d.waitForSignal()

	return nil
}

// handleSetTarget 处理设置目标UID命令
func (d *Daemon) handleSetTarget(uid uint32) error {
	return d.collector.SetTargetUid(uid)
}

// handlePause 处理暂停命令
func (d *Daemon) handlePause() error {
	d.logger.Println("事件推送已暂停")
	return nil
}

// handleResume 处理恢复命令
func (d *Daemon) handleResume() error {
	d.logger.Println("事件推送已恢复")
	return nil
}

// handleShutdown 处理关闭命令
func (d *Daemon) handleShutdown() error {
	d.logger.Println("收到关闭命令")
	d.Shutdown()
	return nil
}

// handleDisconnect 处理与App断连且重连失败
func (d *Daemon) handleDisconnect(err error) {
	d.logger.Printf("与App连接断开且重连失败: %v", err)
	d.ShutdownWithCode(1)
}

// Shutdown 关闭守护进程
func (d *Daemon) Shutdown() {
	d.shutdown(0)
}

// ShutdownWithCode 使用指定退出码关闭守护进程
func (d *Daemon) ShutdownWithCode(exitCode int) {
	d.shutdown(exitCode)
}

func (d *Daemon) shutdown(exitCode int) {
	d.shutdownOnce.Do(func() {
		if !d.running {
			return
		}
		d.running = false

		d.logger.Printf("正在关闭守护进程 (exitCode=%d)...", exitCode)

		// 先停collector，避免继续向Socket发送事件
		if d.collector != nil {
			d.collector.Stop()
		}
		if d.client != nil {
			d.client.Stop()
		}

		// spec § 8.2:daemon 在退出最后一刻自己删 PID 文件。
		// 注意必须在 os.Exit 之前;deferred 函数不会在 os.Exit 时运行。
		d.releasePidFile()

		d.logger.Println("守护进程已关闭")

		// 退出进程
		os.Exit(exitCode)
	})
}

// waitForSignal 等待系统信号
func (d *Daemon) waitForSignal() {
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM, syscall.SIGQUIT)

	sig := <-sigChan
	d.logger.Printf("收到信号: %v", sig)
	d.Shutdown()
}

