package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

// =====================================================
// Symbolizer 单测(spec § 6.1 + § 13 D2 checklist)
// =====================================================

func newSymbolizerForTest() *Symbolizer {
	return NewSymbolizer(log.New(io.Discard, "", 0))
}

// kallsyms 二分查找正确
func TestSymbolizeKernel_Bisect(t *testing.T) {
	s := newSymbolizerForTest()

	// 写一个最小 kallsyms 文件
	dir := t.TempDir()
	path := filepath.Join(dir, "kallsyms")
	content := "" +
		"ffffffc008000000 T _text\n" +
		"ffffffc008001000 T binder_ioctl\n" +
		"ffffffc008002000 T binder_thread_write\n" +
		"ffffffc008003000 T __arm64_sys_ioctl\n"
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatal(err)
	}
	if err := s.LoadKallsyms(path); err != nil {
		t.Fatalf("LoadKallsyms: %v", err)
	}

	// 落在 binder_ioctl + 0x100
	frame := s.SymbolizeKernel(0xffffffc008001100)
	if frame.Symbol != "binder_ioctl" || frame.Offset != 0x100 {
		t.Errorf("expected binder_ioctl+0x100, got %s+0x%x", frame.Symbol, frame.Offset)
	}
	if frame.Quality != StackQualityFull {
		t.Errorf("quality should be FULL, got %d", frame.Quality)
	}

	// 落在 binder_thread_write 起点
	frame = s.SymbolizeKernel(0xffffffc008002000)
	if frame.Symbol != "binder_thread_write" || frame.Offset != 0 {
		t.Errorf("expected binder_thread_write+0, got %s+0x%x", frame.Symbol, frame.Offset)
	}

	// 落在 __arm64_sys_ioctl 区间末端
	frame = s.SymbolizeKernel(0xffffffc008003fff)
	if frame.Symbol != "__arm64_sys_ioctl" {
		t.Errorf("expected __arm64_sys_ioctl, got %s", frame.Symbol)
	}

	// 0 地址 → FAILED
	frame = s.SymbolizeKernel(0)
	if frame.Quality != StackQualityFailed {
		t.Errorf("0 PC should be FAILED, got %d", frame.Quality)
	}

	// 远低于 _text 起点 → FAILED
	frame = s.SymbolizeKernel(0x1000)
	if frame.Quality != StackQualityFailed {
		t.Errorf("below first symbol should be FAILED, got %d", frame.Quality)
	}
}

// /proc/<pid>/maps 解析多种格式行
func TestParseMapsLine_Variants(t *testing.T) {
	cases := []struct {
		line       string
		wantStart  uint64
		wantEnd    uint64
		wantPerms  string
		wantOffset uint64
		wantPath   string
		wantOk     bool
	}{
		{
			line:      "7f8a000000-7f8a001000 r-xp 00000000 fc:01 12345 /system/lib64/libfoo.so",
			wantStart: 0x7f8a000000, wantEnd: 0x7f8a001000,
			wantPerms: "r-xp", wantOffset: 0,
			wantPath: "/system/lib64/libfoo.so", wantOk: true,
		},
		{
			line:      "7f8a002000-7f8a003000 r-xp 0000ab00 00:00 0 [vdso]",
			wantStart: 0x7f8a002000, wantEnd: 0x7f8a003000,
			wantPerms: "r-xp", wantOffset: 0xab00,
			wantPath: "[vdso]", wantOk: true,
		},
		{
			line:      "7f8a004000-7f8a005000 rwxp 00012340 fc:01 9999 /data/app/foo with space/lib.so",
			wantStart: 0x7f8a004000, wantEnd: 0x7f8a005000,
			wantPerms: "rwxp", wantOffset: 0x12340,
			wantPath: "/data/app/foo with space/lib.so", wantOk: true,
		},
		{
			line:   "garbage line",
			wantOk: false,
		},
	}
	for i, tc := range cases {
		got, ok := parseMapsLine(tc.line)
		if ok != tc.wantOk {
			t.Errorf("case %d: ok=%v want %v", i, ok, tc.wantOk)
			continue
		}
		if !ok {
			continue
		}
		if got.start != tc.wantStart || got.end != tc.wantEnd {
			t.Errorf("case %d: range got %x-%x want %x-%x", i, got.start, got.end, tc.wantStart, tc.wantEnd)
		}
		if got.perms != tc.wantPerms {
			t.Errorf("case %d: perms got %s want %s", i, got.perms, tc.wantPerms)
		}
		if got.offset != tc.wantOffset {
			t.Errorf("case %d: offset got %x want %x", i, got.offset, tc.wantOffset)
		}
		if got.path != tc.wantPath {
			t.Errorf("case %d: path got %q want %q", i, got.path, tc.wantPath)
		}
	}
}

// per-pid TTL 命中 / 失效路径(回填 codex review #2 Q4 BLOCKER)
func TestSymbolizer_ProcMapsTTLAndRefresh(t *testing.T) {
	s := newSymbolizerForTest()

	statCalls := 0
	mapsCalls := 0
	s.SetTestProcStatReader(func(pid int) (uint64, error) {
		statCalls++
		return 12345, nil // 同 pid 一直返回同一个 starttime
	})
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		mapsCalls++
		return []procMapsEntry{
			{start: 0x1000, end: 0x2000, perms: "r-xp", path: "/lib/foo.so"},
		}, nil
	})

	// 首次调用 → cache miss → stat + readMaps
	pm, ok := s.getOrLoadMaps(1234)
	if !ok || pm == nil {
		t.Fatal("first lookup failed")
	}
	if statCalls != 1 || mapsCalls != 1 {
		t.Fatalf("after first: stat=%d maps=%d, want 1/1", statCalls, mapsCalls)
	}

	// TTL 内再调用 → 直接命中,不 stat、不 readMaps
	for i := 0; i < 5; i++ {
		s.getOrLoadMaps(1234)
	}
	if statCalls != 1 || mapsCalls != 1 {
		t.Fatalf("within TTL: stat=%d maps=%d, expected unchanged 1/1", statCalls, mapsCalls)
	}

	// 把 cache 改成"过期":手动改 loadedNs
	pm2, _ := s.procMapsCache.get("1234")
	pm2.(*procMaps).loadedNs = time.Now().UnixNano() - int64(2*procMapsTTL)

	// TTL 外再调用 → 触发 stat 验证 + 重读 maps(starttime 没变 → 仍然是同一个进程)
	s.getOrLoadMaps(1234)
	if statCalls != 2 || mapsCalls != 2 {
		t.Fatalf("after TTL expire: stat=%d maps=%d, want 2/2", statCalls, mapsCalls)
	}
}

// starttime 变化 → pid 复用,清旧 cache 重建
func TestSymbolizer_PidReuseDetection(t *testing.T) {
	s := newSymbolizerForTest()

	starttime := uint64(100)
	statCalls := 0
	mapsCalls := 0
	s.SetTestProcStatReader(func(pid int) (uint64, error) {
		statCalls++
		return starttime, nil
	})
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		mapsCalls++
		return []procMapsEntry{
			{start: 0x1000, end: 0x2000, perms: "r-xp", path: fmt.Sprintf("/lib/start%d.so", starttime)},
		}, nil
	})

	pm1, _ := s.getOrLoadMaps(1234)
	if pm1.entries[0].path != "/lib/start100.so" {
		t.Fatalf("first load path mismatch: %s", pm1.entries[0].path)
	}

	// 让 cache 过期 + starttime 变化(进程被杀,pid 复用)
	pmCached, _ := s.procMapsCache.get("1234")
	pmCached.(*procMaps).loadedNs = time.Now().UnixNano() - int64(2*procMapsTTL)
	starttime = 200

	pm2, ok := s.getOrLoadMaps(1234)
	if !ok {
		t.Fatal("second lookup should succeed")
	}
	if pm2.pidStartTime != 200 {
		t.Errorf("starttime should refresh to 200, got %d", pm2.pidStartTime)
	}
	if pm2.entries[0].path != "/lib/start200.so" {
		t.Errorf("path should refresh, got %s", pm2.entries[0].path)
	}
}

// 进程退出(stat 报错)→ 清 cache,本次返回失败
func TestSymbolizer_ProcessExitedDuringRefresh(t *testing.T) {
	s := newSymbolizerForTest()

	alive := true
	s.SetTestProcStatReader(func(pid int) (uint64, error) {
		if !alive {
			return 0, errors.New("ESRCH")
		}
		return 42, nil
	})
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		return []procMapsEntry{
			{start: 0x1000, end: 0x2000, perms: "r-xp", path: "/lib/x.so"},
		}, nil
	})

	pm, ok := s.getOrLoadMaps(1234)
	if !ok || pm == nil {
		t.Fatal("first lookup should succeed")
	}

	// 让 TTL 过期,然后让进程"退出"
	pmCached, _ := s.procMapsCache.get("1234")
	pmCached.(*procMaps).loadedNs = time.Now().UnixNano() - int64(2*procMapsTTL)
	alive = false

	_, ok = s.getOrLoadMaps(1234)
	if ok {
		t.Error("should fail when process exited")
	}
	// cache 应该被清掉
	if _, exists := s.procMapsCache.get("1234"); exists {
		t.Error("cache should be cleared after process exit")
	}
}

// lookup miss 强制刷新该 pid 的 maps 重试一次(spec § 4.3.2 lookup miss 路径)
func TestSymbolizer_LookupMissForcesReload(t *testing.T) {
	s := newSymbolizerForTest()

	// 第一次返回不含目标 pc 的映射;第二次返回包含目标 pc 的映射
	loadCount := 0
	s.SetTestProcStatReader(func(pid int) (uint64, error) {
		return 1, nil
	})
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		loadCount++
		if loadCount == 1 {
			return []procMapsEntry{
				{start: 0x1000, end: 0x2000, perms: "r-xp", path: "/lib/old.so"},
			}, nil
		}
		// 第二次 readMaps:多了一段(模拟 dlopen)
		return []procMapsEntry{
			{start: 0x1000, end: 0x2000, perms: "r-xp", path: "/lib/old.so"},
			{start: 0x5000, end: 0x6000, perms: "r-xp", path: "/lib/new.so"},
		}, nil
	})
	s.SetTestElfLoader(func(path string) (*elfSymbols, error) {
		return &elfSymbols{
			path:            path,
			symtabAvailable: true,
			symbols: []elfSymbol{
				{rva: 0x100, size: 0x100, name: "foo"},
			},
		}, nil
	})

	// 第一次 lookup:pc 落在 [0x5000, 0x6000) 但 cache 里没有 → 触发强制刷新
	frame := s.SymbolizeUser(1234, 0x5100)
	if loadCount != 2 {
		t.Errorf("lookup miss should force reload: loadCount=%d, want 2", loadCount)
	}
	if frame.Module != "/lib/new.so" {
		t.Errorf("module should be /lib/new.so after reload, got %s", frame.Module)
	}
	if frame.Symbol != "foo" {
		t.Errorf("symbol should resolve to foo, got %s", frame.Symbol)
	}
}

// stripped .so 走 .dynsym 降级 → quality DEGRADED
func TestSymbolizer_StrippedDynsymDegraded(t *testing.T) {
	s := newSymbolizerForTest()

	s.SetTestProcStatReader(func(pid int) (uint64, error) { return 1, nil })
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		return []procMapsEntry{
			{start: 0x1000, end: 0x2000, perms: "r-xp", path: "/lib/stripped.so"},
		}, nil
	})
	s.SetTestElfLoader(func(path string) (*elfSymbols, error) {
		return &elfSymbols{
			path:            path,
			symtabAvailable: false, // stripped:仅 .dynsym 命中
			symbols: []elfSymbol{
				{rva: 0x100, size: 0x100, name: "exported_api"},
			},
		}, nil
	})

	frame := s.SymbolizeUser(1234, 0x1150) // 落在 [0x1000, 0x2000),rva=0x150
	if frame.Symbol != "exported_api" {
		t.Errorf("symbol got %s", frame.Symbol)
	}
	if frame.Quality != StackQualityDegraded {
		t.Errorf("stripped .so quality should be DEGRADED, got %d", frame.Quality)
	}
}

// FP_ONLY:帧落在 .so 范围内但没命中任何 symbol(.symtab/.dynsym 都没有该地址)
func TestSymbolizer_FpOnlyWhenSymbolMisses(t *testing.T) {
	s := newSymbolizerForTest()

	s.SetTestProcStatReader(func(pid int) (uint64, error) { return 1, nil })
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		return []procMapsEntry{
			{start: 0x1000, end: 0x9000, perms: "r-xp", path: "/lib/sparse.so"},
		}, nil
	})
	s.SetTestElfLoader(func(path string) (*elfSymbols, error) {
		return &elfSymbols{
			path:            path,
			symtabAvailable: true,
			symbols: []elfSymbol{
				{rva: 0x100, size: 0x10, name: "tiny"},
			},
		}, nil
	})

	// pc=0x5000 落在 [0x1000,0x9000) 但 symbol 区间 [0x100,0x110) 不含 0x4000 rva
	frame := s.SymbolizeUser(1234, 0x5000)
	if frame.Module != "/lib/sparse.so" {
		t.Errorf("module mismatch: %s", frame.Module)
	}
	if frame.Symbol != "" {
		t.Errorf("symbol should be empty for FP_ONLY, got %s", frame.Symbol)
	}
	if frame.Quality != StackQualityFpOnly {
		t.Errorf("quality should be FP_ONLY, got %d", frame.Quality)
	}
}

// LRU evict telemetry 触发(回填 codex MAJOR + spec § 13 D2 checklist:LRU 128 + telemetry)
func TestLRU_EvictTriggers(t *testing.T) {
	evicted := []string{}
	cap := 3
	cache := newLRU(cap, func(key string, current int) {
		evicted = append(evicted, key)
	})
	for i := 0; i < cap+2; i++ {
		cache.put(fmt.Sprintf("k%d", i), i)
	}
	// 应该 evict 最早的两个(k0, k1)
	if len(evicted) != 2 {
		t.Errorf("expected 2 evictions, got %d", len(evicted))
	}
	if evicted[0] != "k0" || evicted[1] != "k1" {
		t.Errorf("evict order: %v", evicted)
	}
	// 容量保持在 cap
	if cache.size() != cap {
		t.Errorf("cache size after evicts: %d, want %d", cache.size(), cap)
	}
}

// LRU 提升最近访问的 key
func TestLRU_PromoteOnGet(t *testing.T) {
	evicted := []string{}
	cache := newLRU(2, func(k string, _ int) { evicted = append(evicted, k) })
	cache.put("a", 1)
	cache.put("b", 2)
	cache.get("a") // 提到 front
	cache.put("c", 3) // 应 evict b
	if len(evicted) != 1 || evicted[0] != "b" {
		t.Errorf("expected b evicted, got %v", evicted)
	}
}

// 内核栈反符号化空 kallsyms → FAILED
func TestSymbolizeKernel_EmptyKallsyms(t *testing.T) {
	s := newSymbolizerForTest()
	frame := s.SymbolizeKernel(0xffff_0000_0000_1000)
	if frame.Quality != StackQualityFailed {
		t.Errorf("empty kallsyms should yield FAILED, got %d", frame.Quality)
	}
}

// =====================================================
// 并发 race 回归(回填 nitpicker B3)
// =====================================================
//
// 用 `go test -race` 跑这个测试必通过 — 之前实现在 TTL 续期路径里原地改写
// cache 内 *procMaps(pm.entries / pm.loadedNs),多 goroutine 并发会触发 race。
// 修复后续期路径**新建 *procMaps 实例**写入 LRU,reader 持有的旧指针是一致快照。
//
// 测试场景:
//   - N=8 reader goroutine 持续 SymbolizeUser 同一 pid + 同一 pc(每事件 32 帧
//     的高频路径)。
//   - 1 个 expirer goroutine 用 `procMapsCache.remove + getOrLoadMaps`
//     强制走"cache miss → 新建对象写入 LRU"路径(不直接 mutate 已 cache 的
//     对象,这正是修复后期望的并发协议)。
//   - 持续 200 ms 跑完;在修复前(原地 mutate),-race 必报"Write/Read at
//     pm.entries"。
func TestSymbolizer_ConcurrentSymbolizeUserNoRace(t *testing.T) {
	s := newSymbolizerForTest()

	var mapsLoadCount atomic.Int64
	s.SetTestProcStatReader(func(pid int) (uint64, error) {
		return 42, nil
	})
	s.SetTestProcMapsReader(func(pid int) ([]procMapsEntry, error) {
		// 每次重读返回不同 entries:若有 race,reader 与 writer 看到的 entries 头会撕裂。
		seq := mapsLoadCount.Add(1)
		return []procMapsEntry{
			{start: 0x1000, end: 0x2000, perms: "r-xp", path: fmt.Sprintf("/lib/seq%d.so", seq)},
		}, nil
	})
	s.SetTestElfLoader(func(path string) (*elfSymbols, error) {
		return &elfSymbols{
			path:            path,
			symtabAvailable: true,
			symbols:         []elfSymbol{{rva: 0x100, size: 0x100, name: "foo"}},
		}, nil
	})

	const pid = 1234
	const pc uint64 = 0x1100

	// 先 prime 一次,把 cache 灌满
	if _, ok := s.getOrLoadMaps(pid); !ok {
		t.Fatal("prime failed")
	}

	stop := make(chan struct{})
	done := make(chan struct{})

	// reader goroutines:N=8,持续 SymbolizeUser
	const N = 8
	for i := 0; i < N; i++ {
		go func() {
			for {
				select {
				case <-stop:
					done <- struct{}{}
					return
				default:
					_ = s.SymbolizeUser(pid, pc)
				}
			}
		}()
	}

	// expirer goroutine:每 5ms 强制 cache miss(remove + 重读),走"新建对象写入
	// LRU"路径。这条路径在修复前会与 reader 撞共享 *procMaps 字段,修复后用
	// 全新对象不撞。
	expireDone := make(chan struct{})
	go func() {
		ticker := time.NewTicker(5 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				expireDone <- struct{}{}
				return
			case <-ticker.C:
				s.procMapsCache.remove("1234")
				_, _ = s.getOrLoadMaps(pid)
			}
		}
	}()

	time.Sleep(200 * time.Millisecond)
	close(stop)
	for i := 0; i < N; i++ {
		<-done
	}
	<-expireDone
	// 不断言具体值,只要 -race 模式下没报错即通过
}
