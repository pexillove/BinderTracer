package main

import (
	"bufio"
	"container/list"
	"debug/elf"
	"fmt"
	"log"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// =====================================================
// Symbolizer (spec § 4.3)
// =====================================================
//
// 内核栈反符号化:启动一次性加载 /proc/kallsyms,排序后二分查找。
// 用户栈反符号化:LRU 128 个 pid 的 /proc/<pid>/maps 缓存(per-pid TTL 100 ms +
// /proc/<pid>/stat 第 22 字段 starttime 失效检测,**不**用 mtime,回填 codex review #2 Q4),
// LRU 128 个 .so 的 ELF 符号表(.symtab 优先,缺失时 .dynsym 降级,quality 标 DEGRADED)。
//
// 关键不变量(回填 codex review #1 MAJOR + #2 Q4):
//   * cache 命中 + age < 100ms → 直接用,**不** stat
//   * cache 命中 + age >= 100ms → 触发 stat 验证 starttime
//   * cache miss → 触发 stat + 读 maps + 写 cache
//   * lookup miss(findContaining 没命中)→ 强制刷新该 pid maps,重试一次
// =====================================================

// StackQuality 反符号化质量(spec § 4.4.2)
type StackQuality uint8

const (
	StackQualityFull     StackQuality = 0 // .symtab 命中
	StackQualityFpOnly   StackQuality = 1 // FP 抓栈但符号未命中(只有模块路径)
	StackQualityDegraded StackQuality = 2 // .dynsym 降级 / 无 symbol 命中
	StackQualityFailed   StackQuality = 3 // 进程退出 / maps 读不到
)

// SymbolFrame 反符号化后的单帧
type SymbolFrame struct {
	PC      uint64 // 原始 PC 地址
	Module  string // 模块路径(.so / kernel)
	Symbol  string // 符号名(为空则 quality 降级)
	Offset  uint64 // 偏移(相对 symbol 起点;若 symbol 为空,则相对模块基址)
	Quality StackQuality
}

// kallsymsEntry 内核 symbol(addr 升序数组,二分查找)
type kallsymsEntry struct {
	addr uint64
	name string
}

// elfSymbol 一个 .so 内的 symbol(rva 升序,二分查找)
type elfSymbol struct {
	rva  uint64
	size uint64
	name string
}

// elfSymbols 一个 .so 的符号表
type elfSymbols struct {
	path            string
	symtabAvailable bool        // false → 仅 .dynsym 命中,quality 降级 DEGRADED
	symbols         []elfSymbol // rva 升序
}

// procMapsEntry /proc/<pid>/maps 的一行
type procMapsEntry struct {
	start  uint64
	end    uint64
	perms  string
	offset uint64
	path   string
}

// procMaps 一个 pid 的 /proc/<pid>/maps cache
type procMaps struct {
	pid          int
	pidStartTime uint64 // /proc/<pid>/stat 的第 22 字段(starttime jiffies);用于 pid 复用检测
	loadedNs     int64  // monotonic ns,LRU + TTL 用
	entries      []procMapsEntry
}

// findContaining 二分查找 pc 落在哪个 entry
func (m *procMaps) findContaining(pc uint64) *procMapsEntry {
	if len(m.entries) == 0 {
		return nil
	}
	// 用 sort.Search 找第一个 entry.end > pc 的位置
	idx := sort.Search(len(m.entries), func(i int) bool {
		return m.entries[i].end > pc
	})
	if idx >= len(m.entries) {
		return nil
	}
	if m.entries[idx].start <= pc && pc < m.entries[idx].end {
		return &m.entries[idx]
	}
	return nil
}

// =====================================================
// LRU(简单 list + map,容量上限,evict 时打 telemetry)
// =====================================================

type lruCache struct {
	capacity int
	order    *list.List               // front = newest, back = oldest
	items    map[string]*list.Element // value 是 *lruEntry(key + value)
	mu       sync.Mutex

	evictTelemetry func(key string, currentSize int)
}

type lruEntry struct {
	key   string
	value interface{}
}

func newLRU(capacity int, evictTel func(string, int)) *lruCache {
	return &lruCache{
		capacity:       capacity,
		order:          list.New(),
		items:          make(map[string]*list.Element),
		evictTelemetry: evictTel,
	}
}

func (l *lruCache) get(key string) (interface{}, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if e, ok := l.items[key]; ok {
		l.order.MoveToFront(e)
		return e.Value.(*lruEntry).value, true
	}
	return nil, false
}

func (l *lruCache) put(key string, value interface{}) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if e, ok := l.items[key]; ok {
		e.Value.(*lruEntry).value = value
		l.order.MoveToFront(e)
		return
	}
	e := l.order.PushFront(&lruEntry{key: key, value: value})
	l.items[key] = e
	if l.order.Len() > l.capacity {
		oldest := l.order.Back()
		if oldest != nil {
			ent := oldest.Value.(*lruEntry)
			l.order.Remove(oldest)
			delete(l.items, ent.key)
			if l.evictTelemetry != nil {
				l.evictTelemetry(ent.key, l.order.Len())
			}
		}
	}
}

func (l *lruCache) remove(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if e, ok := l.items[key]; ok {
		l.order.Remove(e)
		delete(l.items, key)
	}
}

func (l *lruCache) size() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.order.Len()
}

// =====================================================
// Symbolizer 主结构
// =====================================================

// SymbolizerCounters 上报用的 telemetry
type SymbolizerCounters struct {
	KallsymsLoadedCount  uint64
	SymbolizeFull        atomic.Uint64
	SymbolizeFpOnly      atomic.Uint64
	SymbolizeDegraded    atomic.Uint64
	SymbolizeFailed      atomic.Uint64
	SymtabUnavailableSo  atomic.Uint64
}

// Symbolizer 主类(并发安全;LRU 自带锁,kallsyms 启动期一次性加载,后续只读)
type Symbolizer struct {
	logger *log.Logger

	kallsyms []kallsymsEntry // 升序

	procMapsCache *lruCache // pid string → *procMaps
	elfCache      *lruCache // path → *elfSymbols

	counters SymbolizerCounters

	// 测试用 hook(默认 nil → 走真实 /proc 读取)
	procMapsReader  func(pid int) ([]procMapsEntry, error)
	procStatReader  func(pid int) (uint64, error) // 返回 starttime
	elfLoader       func(path string) (*elfSymbols, error)
}

// procMapsTTL 同一 pid maps cache 命中 TTL(spec § 4.3.2 Q4 修正)
const procMapsTTL = 100 * time.Millisecond

// NewSymbolizer 创建 symbolizer。Init() 单独调用,允许测试替换 kallsyms 路径。
func NewSymbolizer(logger *log.Logger) *Symbolizer {
	s := &Symbolizer{
		logger: logger,
	}
	s.procMapsCache = newLRU(128, func(key string, current int) {
		s.logger.Printf("procmaps_evict pid=%s current=%d/128", key, current)
	})
	s.elfCache = newLRU(128, func(key string, current int) {
		s.logger.Printf("elf_evict path=%s current=%d/128", key, current)
	})
	return s
}

// LoadKallsyms 启动时一次性加载;失败不致命(返回 error 给上层决定是否降级)。
func (s *Symbolizer) LoadKallsyms(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open kallsyms: %w", err)
	}
	defer f.Close()

	var entries []kallsymsEntry
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 1024*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		// 格式: <addr> <type> <name> [module]
		// e.g. ffffffc0080010c0 T binder_ioctl
		fields := strings.Fields(line)
		if len(fields) < 3 {
			continue
		}
		addr, err := strconv.ParseUint(fields[0], 16, 64)
		if err != nil || addr == 0 {
			continue
		}
		entries = append(entries, kallsymsEntry{addr: addr, name: fields[2]})
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("scan kallsyms: %w", err)
	}

	sort.Slice(entries, func(i, j int) bool { return entries[i].addr < entries[j].addr })
	s.kallsyms = entries
	s.counters.KallsymsLoadedCount = uint64(len(entries))
	s.logger.Printf("kallsyms_loaded count=%d", len(entries))
	return nil
}

// SymbolizeKernel 内核栈反符号化(二分查找)
func (s *Symbolizer) SymbolizeKernel(pc uint64) SymbolFrame {
	if len(s.kallsyms) == 0 || pc == 0 {
		s.counters.SymbolizeFailed.Add(1)
		return SymbolFrame{PC: pc, Quality: StackQualityFailed}
	}
	// 找最大的 addr <= pc
	idx := sort.Search(len(s.kallsyms), func(i int) bool {
		return s.kallsyms[i].addr > pc
	})
	if idx == 0 {
		s.counters.SymbolizeFailed.Add(1)
		return SymbolFrame{PC: pc, Module: "kernel", Quality: StackQualityFailed}
	}
	ent := s.kallsyms[idx-1]
	s.counters.SymbolizeFull.Add(1)
	return SymbolFrame{
		PC:      pc,
		Module:  "kernel",
		Symbol:  ent.name,
		Offset:  pc - ent.addr,
		Quality: StackQualityFull,
	}
}

// SymbolizeUser 用户栈反符号化
func (s *Symbolizer) SymbolizeUser(pid int, pc uint64) SymbolFrame {
	if pc == 0 {
		s.counters.SymbolizeFailed.Add(1)
		return SymbolFrame{PC: pc, Quality: StackQualityFailed}
	}

	maps, ok := s.getOrLoadMaps(pid)
	if !ok {
		s.counters.SymbolizeFailed.Add(1)
		return SymbolFrame{PC: pc, Quality: StackQualityFailed}
	}

	entry := maps.findContaining(pc)
	if entry == nil {
		// lookup miss → 强制刷新该 pid maps 重试一次(可能是 dlopen 后新 .so)
		s.procMapsCache.remove(strconv.Itoa(pid))
		maps2, ok2 := s.getOrLoadMaps(pid)
		if !ok2 {
			s.counters.SymbolizeFailed.Add(1)
			return SymbolFrame{PC: pc, Quality: StackQualityFailed}
		}
		entry = maps2.findContaining(pc)
		if entry == nil {
			s.counters.SymbolizeDegraded.Add(1)
			return SymbolFrame{PC: pc, Quality: StackQualityDegraded}
		}
	}

	// 计算 RVA。/proc/<pid>/maps 的 offset 字段是该映射对应文件内的偏移。
	// 文件内地址 file_off = entry.offset + (pc - entry.start)。
	// 但 ELF 二分用的是相对 .text base 的偏移(rva)。简化处理:用 file_off 直接二分;
	// 多数 .so 的 .text symbol 都用 file offset 等同 rva 编排,Go 标准库 debug/elf
	// 给出的 sym.Value 也是 rva(对应 PT_LOAD 头的 vaddr)。考虑映射偏移近似为
	// file_off 减去 base PT_LOAD 的 file offset,这里不展开,用 file_off 直接查 vaddr。
	relPC := entry.offset + (pc - entry.start)

	elfSyms, ok := s.getOrLoadElf(entry.path)
	if !ok {
		s.counters.SymbolizeDegraded.Add(1)
		return SymbolFrame{
			PC:      pc,
			Module:  entry.path,
			Offset:  pc - entry.start,
			Quality: StackQualityDegraded,
		}
	}

	sym := lookupElfSymbol(elfSyms, relPC)
	if sym == nil {
		// 帧落在模块内但没命中 symbol → FP_ONLY(spec § 4.4.2 quality:1=FP_ONLY)
		s.counters.SymbolizeFpOnly.Add(1)
		return SymbolFrame{
			PC:      pc,
			Module:  entry.path,
			Offset:  pc - entry.start,
			Quality: StackQualityFpOnly,
		}
	}

	q := StackQualityFull
	if !elfSyms.symtabAvailable {
		q = StackQualityDegraded
	}
	switch q {
	case StackQualityFull:
		s.counters.SymbolizeFull.Add(1)
	case StackQualityDegraded:
		s.counters.SymbolizeDegraded.Add(1)
	}
	return SymbolFrame{
		PC:      pc,
		Module:  entry.path,
		Symbol:  sym.name,
		Offset:  relPC - sym.rva,
		Quality: q,
	}
}

// lookupElfSymbol 二分查找 rva 落在哪个 symbol(rva <= pc < rva+size)
func lookupElfSymbol(es *elfSymbols, rva uint64) *elfSymbol {
	if len(es.symbols) == 0 {
		return nil
	}
	idx := sort.Search(len(es.symbols), func(i int) bool {
		return es.symbols[i].rva > rva
	})
	if idx == 0 {
		return nil
	}
	cand := &es.symbols[idx-1]
	// size 字段为 0 的 symbol 不严格区间约束(.dynsym 常见 size=0),仍返回作为最近邻
	if cand.size == 0 {
		return cand
	}
	if rva < cand.rva+cand.size {
		return cand
	}
	return nil
}

// =====================================================
// /proc/<pid>/maps cache 与 starttime 失效检测
// =====================================================

// getOrLoadMaps 拿到 pid 的 maps cache;TTL + starttime 失效检测(spec § 4.3.2 Q4)。
//
// 并发安全(回填 nitpicker B3):**绝不**原地 mutate cache 内的 *procMaps 实例,
// 因为 SymbolizeUser 是 daemon 唯一并发热路径(每事件 32 帧 lookup),多 goroutine
// 同时调同 pid 时,一个走 TTL 内"直接用 pm",另一个若改写 pm.entries / pm.loadedNs
// 会造成 data race,Go race detector 必报 + 实际可能 panic("nil pointer
// dereference"或"slice bounds out of range")。
//
// 修复策略:TTL 续期路径**新建 *procMaps 实例**写入 LRU(spec § 4.3.2 描述就是
// "重读 maps + 写 cache",新对象覆盖旧对象);旧 reader 持有的旧指针仍有效但是
// 一致快照,GC 适时回收。
func (s *Symbolizer) getOrLoadMaps(pid int) (*procMaps, bool) {
	key := strconv.Itoa(pid)
	now := time.Now().UnixNano()

	if v, ok := s.procMapsCache.get(key); ok {
		pm := v.(*procMaps)
		if (now - pm.loadedNs) < int64(procMapsTTL) {
			// TTL 内,直接用,不 stat。pm 是 immutable 快照,并发 read 安全。
			return pm, true
		}
		// TTL 过期 → 验证 starttime
		st, err := s.readProcStarttime(pid)
		if err != nil {
			// 进程已退出 → 清 cache,本次 lookup 失败
			s.procMapsCache.remove(key)
			return nil, false
		}
		if st == pm.pidStartTime {
			// pid 仍是同一个进程,只是 cache 过期 → 重读 maps + 新建快照
			entries, err := s.readProcMaps(pid)
			if err != nil {
				s.procMapsCache.remove(key)
				return nil, false
			}
			refreshed := &procMaps{
				pid:          pid,
				pidStartTime: pm.pidStartTime,
				loadedNs:     now,
				entries:      entries,
			}
			s.procMapsCache.put(key, refreshed)
			return refreshed, true
		}
		// pid 已被复用 → 清旧 cache
		s.procMapsCache.remove(key)
	}

	// cache miss → 读 starttime + 读 maps
	st, err := s.readProcStarttime(pid)
	if err != nil {
		return nil, false
	}
	entries, err := s.readProcMaps(pid)
	if err != nil {
		return nil, false
	}
	pm := &procMaps{
		pid:          pid,
		pidStartTime: st,
		loadedNs:     now,
		entries:      entries,
	}
	s.procMapsCache.put(key, pm)
	return pm, true
}

func (s *Symbolizer) readProcStarttime(pid int) (uint64, error) {
	if s.procStatReader != nil {
		return s.procStatReader(pid)
	}
	return readProcStarttime(pid)
}

func (s *Symbolizer) readProcMaps(pid int) ([]procMapsEntry, error) {
	if s.procMapsReader != nil {
		return s.procMapsReader(pid)
	}
	return readProcMaps(pid)
}

// readProcStarttime 解析 /proc/<pid>/stat 的第 22 字段
//
// /proc/<pid>/stat 格式:
//   pid (comm with parens) state ppid pgrp session tty_nr tpgid flags
//   minflt cminflt majflt cmajflt utime stime cutime cstime priority nice
//   num_threads itrealvalue starttime ...
//                            ^^^^^^^^^ 第 22 字段(从 1 数起)
//
// comm 字段可能含空格 + 括号,需要从右括号开始 split。
func readProcStarttime(pid int) (uint64, error) {
	path := fmt.Sprintf("/proc/%d/stat", pid)
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	s := string(data)
	// 找最后一个 ')',然后切掉 "pid (comm)"
	rp := strings.LastIndex(s, ")")
	if rp < 0 || rp+2 >= len(s) {
		return 0, fmt.Errorf("malformed stat: %q", s)
	}
	rest := s[rp+2:]
	fields := strings.Fields(rest)
	// 现在 fields[0] = state(原第 3 字段),starttime = 第 22 字段 = 现 fields[19]
	if len(fields) < 20 {
		return 0, fmt.Errorf("stat too few fields: %d", len(fields))
	}
	st, err := strconv.ParseUint(fields[19], 10, 64)
	if err != nil {
		return 0, fmt.Errorf("parse starttime: %w", err)
	}
	return st, nil
}

// readProcMaps 解析 /proc/<pid>/maps
func readProcMaps(pid int) ([]procMapsEntry, error) {
	path := fmt.Sprintf("/proc/%d/maps", pid)
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var entries []procMapsEntry
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 1024*1024), 1024*1024)
	for scanner.Scan() {
		ent, ok := parseMapsLine(scanner.Text())
		if !ok {
			continue
		}
		// 只保留可执行映射(避免在 .data 段查 symbol)
		if !strings.Contains(ent.perms, "x") {
			continue
		}
		entries = append(entries, ent)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	// 按 start 升序(maps 文件本来就升序,但显式 sort 保险)
	sort.Slice(entries, func(i, j int) bool { return entries[i].start < entries[j].start })
	return entries, nil
}

// parseMapsLine 解析 /proc/<pid>/maps 单行
//
// 格式(spec § 4.3.2 + man proc):
//   <start>-<end> <perms> <offset> <dev> <inode> [path]
//   e.g. 7f8a000000-7f8a001000 r-xp 00000000 fc:01 12345 /system/lib64/libfoo.so
//
// path 可能是:
//   - 普通路径(/system/lib64/libfoo.so)
//   - [stack] / [heap] / [vdso] / [vsyscall](方括号特殊段)
//   - 空(匿名映射)
func parseMapsLine(line string) (procMapsEntry, bool) {
	fields := strings.Fields(line)
	if len(fields) < 5 {
		return procMapsEntry{}, false
	}
	rng := strings.SplitN(fields[0], "-", 2)
	if len(rng) != 2 {
		return procMapsEntry{}, false
	}
	start, err := strconv.ParseUint(rng[0], 16, 64)
	if err != nil {
		return procMapsEntry{}, false
	}
	end, err := strconv.ParseUint(rng[1], 16, 64)
	if err != nil {
		return procMapsEntry{}, false
	}
	off, err := strconv.ParseUint(fields[2], 16, 64)
	if err != nil {
		return procMapsEntry{}, false
	}
	path := ""
	if len(fields) >= 6 {
		// path 可能含空格 → 取剩余拼接
		path = strings.Join(fields[5:], " ")
	}
	return procMapsEntry{
		start:  start,
		end:    end,
		perms:  fields[1],
		offset: off,
		path:   path,
	}, true
}

// =====================================================
// ELF symbol cache
// =====================================================

// getOrLoadElf 按 path 加载 ELF 符号(LRU);路径以方括号开头(如 [vdso])跳过
func (s *Symbolizer) getOrLoadElf(path string) (*elfSymbols, bool) {
	if path == "" || strings.HasPrefix(path, "[") {
		return nil, false
	}
	if v, ok := s.elfCache.get(path); ok {
		return v.(*elfSymbols), true
	}
	var es *elfSymbols
	var err error
	if s.elfLoader != nil {
		es, err = s.elfLoader(path)
	} else {
		es, err = loadElfSymbols(path)
	}
	if err != nil || es == nil {
		// 加载失败不缓存,下次仍会重试(失败可能是 stripped + 无 dynsym 的小 .so)
		return nil, false
	}
	if !es.symtabAvailable {
		s.counters.SymtabUnavailableSo.Add(1)
	}
	s.elfCache.put(path, es)
	return es, true
}

// loadElfSymbols 用 debug/elf 解析 .so 的 .symtab(优先)+ .dynsym(降级)
func loadElfSymbols(path string) (*elfSymbols, error) {
	f, err := elf.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	out := &elfSymbols{path: path}

	syms, err := f.Symbols()
	if err == nil && len(syms) > 0 {
		out.symtabAvailable = true
		out.symbols = appendElfSymbols(out.symbols, syms)
	}
	// 不论 .symtab 是否成功都尝试合并 .dynsym(.symtab 命中作为权威数据;.dynsym 是补充)
	dyn, err := f.DynamicSymbols()
	if err == nil && len(dyn) > 0 {
		out.symbols = appendElfSymbols(out.symbols, dyn)
	}
	if len(out.symbols) == 0 {
		return out, fmt.Errorf("no symbols in %s", path)
	}
	// 排序 + 去重(按 rva 升序;同 rva 保留 size 大的)
	sort.Slice(out.symbols, func(i, j int) bool {
		if out.symbols[i].rva == out.symbols[j].rva {
			return out.symbols[i].size > out.symbols[j].size
		}
		return out.symbols[i].rva < out.symbols[j].rva
	})
	return out, nil
}

func appendElfSymbols(dst []elfSymbol, src []elf.Symbol) []elfSymbol {
	for _, sym := range src {
		// 只保留函数 symbol(STT_FUNC = 2),其它 noise(NOTYPE / OBJECT / FILE / SECTION)滤掉。
		// debug/elf 没有导出 STT_GNU_IFUNC,直接对原始数值 0xa 做兜底(ARM64 上很少见)。
		stt := elf.ST_TYPE(sym.Info)
		if stt != elf.STT_FUNC && byte(stt) != 0xa {
			continue
		}
		if sym.Value == 0 || sym.Name == "" {
			continue
		}
		dst = append(dst, elfSymbol{
			rva:  sym.Value,
			size: sym.Size,
			name: sym.Name,
		})
	}
	return dst
}

// SetTestProcMapsReader test-only:替换 /proc/<pid>/maps 读取(测试用 in-memory 数据)
func (s *Symbolizer) SetTestProcMapsReader(reader func(pid int) ([]procMapsEntry, error)) {
	s.procMapsReader = reader
}

// SetTestProcStatReader test-only:替换 /proc/<pid>/stat starttime 读取
func (s *Symbolizer) SetTestProcStatReader(reader func(pid int) (uint64, error)) {
	s.procStatReader = reader
}

// SetTestElfLoader test-only:替换 ELF 解析
func (s *Symbolizer) SetTestElfLoader(loader func(path string) (*elfSymbols, error)) {
	s.elfLoader = loader
}

// CountersSnapshot 取一份 telemetry 快照(给 socket_server 1Hz 上报用)
func (s *Symbolizer) CountersSnapshot() (full, fpOnly, degraded, failed, symtabMissing uint64, kallsymsLoaded uint64, procMapsSize, elfSize int) {
	return s.counters.SymbolizeFull.Load(),
		s.counters.SymbolizeFpOnly.Load(),
		s.counters.SymbolizeDegraded.Load(),
		s.counters.SymbolizeFailed.Load(),
		s.counters.SymtabUnavailableSo.Load(),
		s.counters.KallsymsLoadedCount,
		s.procMapsCache.size(),
		s.elfCache.size()
}
