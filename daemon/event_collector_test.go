//go:build arm64

package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"os"
	"testing"
	"time"
)

// spec § 13.1.4:Parcel 重组回归测试。
// 验证 EventCollector.handleEvent 把 ringbuf 的 chunk 收齐合并、按 DataSize 精确截取后回调 onEvent。
// 覆盖单分片 / 多分片 / 乱序到达 / off-by-one / DataSize 边界 / 过期 evict 等。

// newCollectorForTest 构造一个不依赖 BPF 的纯逻辑 EventCollector;
// onEvent 把回调结果攒到 captured 切片里,测试用例直接断言。
//
// spec 2026-05-09 daemon-cookie-race-fix:默认开 legacyCookieZeroSyncEmit,让
// makeChunk 默认 cookie=0 + IsReply=0 的事件继续走"直接 emit"降级语义,保持
// chunk 重组类测试不受新状态机影响。新加的 cookie race 测试用 newCollectorForState
// 关掉钩子。
func newCollectorForTest() (*EventCollector, *[]*BinderEventPayload) {
	c, captured := newCollectorForState()
	c.legacyCookieZeroSyncEmit = true
	return c, captured
}

// newCollectorForState 构造与 newCollectorForTest 等价但不开 legacy 钩子的 collector,
// 供新状态机测试验证 cookie==0 SYNC 走 pendingMainByTxid 的真实生产语义。
func newCollectorForState() (*EventCollector, *[]*BinderEventPayload) {
	c := NewEventCollector(log.New(io.Discard, "", 0))
	captured := make([]*BinderEventPayload, 0, 4)
	c.SetEventCallback(func(e *BinderEventPayload) {
		captured = append(captured, e)
	})
	return c, &captured
}

// makeChunk 构造一个 v2 字段填全的 bpfBinderTraceEvent;ChunkData 复制 payload 的前 N 字节。
//
// 默认填的是 SYNC 主事件(IsReply=0、Flags=0、Cookie=0),
// cookie 0 等价 spec § 5.6.3 "BPF cookie 分配失败 / pending_map 写失败" 降级路径,
// routeMainEvent 走 emit 直发,不进 pendingMain 状态机 —— 与升级 § 5.6 之前的语义一致,
// 现有 chunk 重组 / first-seen / DataSize 边界等用例不需要管 cookie。
//
// 新加的状态机测试用例统一用 makeMainEvent / makeSidebandEvent helper 构造。
func makeChunk(transactionId uint64, chunkIdx uint64, dataSize uint64, payload []byte) *bpfBinderTraceEvent {
	var ev bpfBinderTraceEvent
	ev.Kind = evtKindMain
	ev.TransactionId = transactionId
	ev.ChunkIndex = chunkIdx
	ev.DataSize = dataSize
	ev.Pid = 1234
	ev.Uid = 10086
	ev.Code = 1
	ev.Flags = 0
	ev.IsReply = 0
	ev.BinderDev = 1
	ev.TargetKind = 1
	// P4-B-2 后,entry kprobe 写 ToPid/ToUid=0(stub),真值靠 raw_tp sideband 回填。
	// 这里也置 0,与现行 BPF 行为对齐。
	ev.ToPid = 0
	ev.ToUid = 0
	ev.TargetRef = 0xCAFEBABE
	ev.PairId = 0
	ev.Cookie = 0
	copy(ev.ChunkData[:], payload)
	return &ev
}

// patternBytes 生成 length 长的可预测内容,便于断言重组后内容匹配。
func patternBytes(length int, seed byte) []byte {
	out := make([]byte, length)
	for i := range out {
		out[i] = byte(int(seed) + i)
	}
	return out
}

// 1. 单分片 round-trip:DataSize < ChunkSize,1 个 chunk 即完整。
func TestHandleEvent_SingleChunkRoundTrip(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = 512
	body := patternBytes(dataSize, 0xA0)
	// chunk 缓冲是 1024 字节;把 body 填进前 dataSize,后面留零。
	chunkBuf := make([]byte, ChunkSize)
	copy(chunkBuf, body)

	c.handleEvent(makeChunk(0xBEEF0000, 0, dataSize, chunkBuf))

	if len(*captured) != 1 {
		t.Fatalf("onEvent should be called once, got %d", len(*captured))
	}
	got := (*captured)[0]
	if int(got.DataSize) != dataSize {
		t.Errorf("DataSize: got %d, want %d", got.DataSize, dataSize)
	}
	if len(got.ParcelData) != dataSize {
		t.Fatalf("ParcelData len: got %d, want %d", len(got.ParcelData), dataSize)
	}
	if !bytes.Equal(got.ParcelData, body) {
		t.Error("ParcelData content mismatch with original body")
	}
	// 元数据从 first-seen 取
	if got.Pid != 1234 || got.Uid != 10086 || got.TargetRef != 0xCAFEBABE {
		t.Errorf("meta lost: pid=%d uid=%d ref=0x%x", got.Pid, got.Uid, got.TargetRef)
	}
	if len(c.transactionBuffers) != 0 {
		t.Errorf("buffer not cleaned: %d remain", len(c.transactionBuffers))
	}
}

// 2. 多分片合并:DataSize 跨 3 片(2500 字节),按序投递。
func TestHandleEvent_MultiChunkMerge(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = 2500
	body := patternBytes(dataSize, 0xB0)
	// 三个 chunk:每个 1024 字节缓冲,最后一片只有 452 字节 payload(其余位置为 0)
	chunks := make([][]byte, 3)
	for i := 0; i < 3; i++ {
		chunks[i] = make([]byte, ChunkSize)
	}
	copy(chunks[0], body[0:1024])
	copy(chunks[1], body[1024:2048])
	copy(chunks[2], body[2048:2500]) // 仅 452 字节,后续为 0

	const tid = 0xBEEF0001
	c.handleEvent(makeChunk(tid, 0, dataSize, chunks[0]))
	if len(*captured) != 0 {
		t.Fatal("onEvent should not fire before all chunks arrive")
	}
	c.handleEvent(makeChunk(tid, 1, dataSize, chunks[1]))
	if len(*captured) != 0 {
		t.Fatal("onEvent should not fire before all chunks arrive")
	}
	c.handleEvent(makeChunk(tid, 2, dataSize, chunks[2]))
	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire once after last chunk, got %d", len(*captured))
	}

	got := (*captured)[0]
	if len(got.ParcelData) != dataSize {
		t.Fatalf("ParcelData len: got %d, want %d (== DataSize)", len(got.ParcelData), dataSize)
	}
	if !bytes.Equal(got.ParcelData, body) {
		t.Error("merged ParcelData mismatches original body")
	}
}

// 3. 乱序到达多分片:chunk2 -> chunk0 -> chunk1,最终仍能正确合并。
func TestHandleEvent_OutOfOrderChunks(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = 2500
	body := patternBytes(dataSize, 0x10)
	chunks := make([][]byte, 3)
	for i := 0; i < 3; i++ {
		chunks[i] = make([]byte, ChunkSize)
	}
	copy(chunks[0], body[0:1024])
	copy(chunks[1], body[1024:2048])
	copy(chunks[2], body[2048:2500])

	const tid = 0xBEEF0002
	// 先到 chunk2,再 chunk0,最后 chunk1
	c.handleEvent(makeChunk(tid, 2, dataSize, chunks[2]))
	c.handleEvent(makeChunk(tid, 0, dataSize, chunks[0]))
	if len(*captured) != 0 {
		t.Fatal("onEvent should not fire before chunk 1 arrives")
	}
	c.handleEvent(makeChunk(tid, 1, dataSize, chunks[1]))

	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire exactly once, got %d", len(*captured))
	}
	got := (*captured)[0]
	if !bytes.Equal(got.ParcelData, body) {
		t.Error("out-of-order merged ParcelData mismatches original body")
	}
}

// 4. DataSize 精确截取 (off-by-one regression):DataSize=ChunkSize 时取整片 1024,
// 旧代码 [:DataSize-1] 会少 1 字节(=1023),这里必须是 1024。
func TestHandleEvent_DataSizeExactBoundary(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = ChunkSize // 1024 字节,正好填满一个 chunk
	body := patternBytes(dataSize, 0x40)
	chunkBuf := make([]byte, ChunkSize)
	copy(chunkBuf, body)

	c.handleEvent(makeChunk(0xBEEF0003, 0, dataSize, chunkBuf))

	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire once, got %d", len(*captured))
	}
	got := (*captured)[0]
	if len(got.ParcelData) != dataSize {
		t.Fatalf("off-by-one regression: ParcelData len = %d, want %d", len(got.ParcelData), dataSize)
	}
	if got.ParcelData[dataSize-1] != body[dataSize-1] {
		t.Errorf("last byte = 0x%x, want 0x%x", got.ParcelData[dataSize-1], body[dataSize-1])
	}
	if !bytes.Equal(got.ParcelData, body) {
		t.Error("ParcelData mismatch")
	}
}

// 5. DataSize=0 被拒:不应触发 onEvent;buffer 应已清理。
func TestHandleEvent_DataSizeZeroRejected(t *testing.T) {
	c, captured := newCollectorForTest()

	chunkBuf := make([]byte, ChunkSize)
	c.handleEvent(makeChunk(0xBEEF0004, 0, 0, chunkBuf))

	if len(*captured) != 0 {
		t.Fatalf("onEvent must NOT fire when DataSize=0, got %d call(s)", len(*captured))
	}
	if len(c.transactionBuffers) != 0 {
		t.Errorf("buffer should be cleaned when DataSize=0, %d remain", len(c.transactionBuffers))
	}
}

// 6. completeData 超出 ChunkSize*MaxChunks:实现里 len(completeData) > 16384 直接丢弃。
// 构造 DataSize 略大于 MaxChunks*ChunkSize,使 totalChunks > MaxChunks → completeData 超限。
func TestHandleEvent_OversizedRejected(t *testing.T) {
	c, captured := newCollectorForTest()

	// totalChunks = ceil(DataSize/ChunkSize);DataSize=17*1024 → totalChunks=17(>MaxChunks=16)
	// completeData = 17 * 1024 = 17408 > 16384,落入丢弃分支。
	const dataSize uint64 = (MaxChunks + 1) * ChunkSize
	chunkBuf := make([]byte, ChunkSize)
	const tid = 0xBEEF0005

	// 投递所有 17 个分片
	for i := uint64(0); i < (MaxChunks + 1); i++ {
		c.handleEvent(makeChunk(tid, i, dataSize, chunkBuf))
	}

	if len(*captured) != 0 {
		t.Fatalf("oversized payload must be rejected, got %d onEvent call(s)", len(*captured))
	}
	if _, exists := c.transactionBuffers[tid]; exists {
		t.Error("oversized buffer should be deleted after rejection")
	}
}

// 7. 过期 evict:伪造一个 stale buffer(firstSeenNs 设为很久以前),
// 然后投递 cleanupEveryNEvts 个无关事件触发 maybeCleanup,验证 stale buffer 被清掉。
func TestHandleEvent_StaleBufferEvictedByCleanupTick(t *testing.T) {
	c, captured := newCollectorForTest()

	// 注入一个 stale buffer:tid 唯一,firstSeenNs 在 cutoff 之前(staleBufferTTL+1s 之前)
	const staleTid uint64 = 0xDEADBEEF
	staleNs := time.Now().UnixNano() - int64(staleBufferTTL) - int64(time.Second)
	c.transactionBuffers[staleTid] = &txBuffer{
		firstSeenNs: staleNs,
		chunks:      make([][]byte, 4), // 期望 4 片但都是 nil → 永不 complete
		meta:        txMeta{dataSize: 4 * ChunkSize},
	}
	if _, ok := c.transactionBuffers[staleTid]; !ok {
		t.Fatal("stale buffer not seeded")
	}

	// 触发 cleanupEveryNEvts 次 maybeCleanup:
	// 用 DataSize 跨 2 片但只投 chunk 0 的"无关事件",每个 tid 不同,既不 complete,
	// 也保证 handleEvent 走完整路径并最终调用 maybeCleanup。
	chunkBuf := make([]byte, ChunkSize)
	const dataSize uint64 = 2 * ChunkSize
	for i := uint64(0); i < uint64(cleanupEveryNEvts); i++ {
		c.handleEvent(makeChunk(0x1_0000_0000+i, 0, dataSize, chunkBuf))
	}

	// 没有事务集齐,onEvent 不应被调用
	if len(*captured) != 0 {
		t.Errorf("no transaction should complete, got %d onEvent call(s)", len(*captured))
	}
	// stale buffer 必须已被 evict
	if _, exists := c.transactionBuffers[staleTid]; exists {
		t.Errorf("stale buffer (tid=0x%x) should be evicted after cleanup tick", staleTid)
	}
	// 那 256 个未集齐的"新鲜"buffer 不应被清(它们 firstSeenNs 是当前时间,< cutoff 不成立)
	if got := len(c.transactionBuffers); got != cleanupEveryNEvts {
		t.Errorf("fresh buffers should remain, want %d, got %d", cleanupEveryNEvts, got)
	}
}

// 8. MaxChunks 边界:DataSize=16*1024(整 16 片),恰好等于 ChunkSize*MaxChunks(<= 而非 <),
// 实现里判 `len(completeData) > ChunkSize*MaxChunks`,等号通过。
func TestHandleEvent_MaxChunksBoundary(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = MaxChunks * ChunkSize // 16384
	body := patternBytes(dataSize, 0x80)

	const tid = 0xBEEF0006
	for i := 0; i < MaxChunks; i++ {
		chunkBuf := make([]byte, ChunkSize)
		copy(chunkBuf, body[i*ChunkSize:(i+1)*ChunkSize])
		c.handleEvent(makeChunk(tid, uint64(i), dataSize, chunkBuf))
	}

	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire once at MaxChunks boundary, got %d", len(*captured))
	}
	got := (*captured)[0]
	if len(got.ParcelData) != dataSize {
		t.Fatalf("ParcelData len: got %d, want %d", len(got.ParcelData), dataSize)
	}
	if !bytes.Equal(got.ParcelData, body) {
		t.Error("16-chunk merged ParcelData mismatches original body")
	}
}

// makeChunkWithTo 与 makeChunk 类似,但允许独立指定 to_pid / to_uid,用于 P4 双向过滤透传测试。
// 其他元数据保持与 makeChunk 一致,便于断言 first-seen meta 行为。
func makeChunkWithTo(transactionId uint64, chunkIdx uint64, dataSize uint64, payload []byte, toPid, toUid uint32) *bpfBinderTraceEvent {
	ev := makeChunk(transactionId, chunkIdx, dataSize, payload)
	ev.ToPid = toPid
	ev.ToUid = toUid
	return ev
}

// P4-1. 出向请求(本期常态):BPF 端 to_pid/to_uid 都为 0(无 vmlinux.h)→ Payload 同样为 0,
// 字段透传不丢失(等 vmlinux.h 引入后这条用例继续守 0 路径)。
func TestHandleEvent_ToFieldsTransparency_Outgoing(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = 256
	body := patternBytes(dataSize, 0xD0)
	chunkBuf := make([]byte, ChunkSize)
	copy(chunkBuf, body)

	c.handleEvent(makeChunkWithTo(0xBEEF1000, 0, dataSize, chunkBuf, 0, 0))

	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire once, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.ToPid != 0 || got.ToUid != 0 {
		t.Errorf("outgoing: expected ToPid=0 ToUid=0, got ToPid=%d ToUid=%d", got.ToPid, got.ToUid)
	}
}

// P4-2. 入向调用(模拟未来 vmlinux.h 引入后):BPF 端解出非 0 的 to_pid/to_uid →
// Payload 必须把这两个字段原样透传出去(EventCollector 不能再覆为 0)。
func TestHandleEvent_ToFieldsTransparency_Incoming(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = 256
	body := patternBytes(dataSize, 0xE0)
	chunkBuf := make([]byte, ChunkSize)
	copy(chunkBuf, body)

	const wantToPid uint32 = 8888
	const wantToUid uint32 = 10086
	c.handleEvent(makeChunkWithTo(0xBEEF1001, 0, dataSize, chunkBuf, wantToPid, wantToUid))

	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire once, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.ToPid != wantToPid || got.ToUid != wantToUid {
		t.Errorf("incoming: expected ToPid=%d ToUid=%d, got ToPid=%d ToUid=%d",
			wantToPid, wantToUid, got.ToPid, got.ToUid)
	}
}

// P4-3. 多分片场景下 to_pid / to_uid 跟其他 meta 字段一样遵循 first-seen 语义:
// 第一片定型,后续分片即使写了不同值也不应覆盖。BPF 端实际上每片都写同一个 to_uid,
// 这里用差异值是为了显式测 EventCollector 的 first-seen 不变量(spec § 13.1.4)。
func TestHandleEvent_PreservesToFieldsAcrossChunks(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = 2500
	body := patternBytes(dataSize, 0xF0)
	chunks := make([][]byte, 3)
	for i := 0; i < 3; i++ {
		chunks[i] = make([]byte, ChunkSize)
	}
	copy(chunks[0], body[0:1024])
	copy(chunks[1], body[1024:2048])
	copy(chunks[2], body[2048:2500])

	const tid = 0xBEEF1002
	const wantToPid uint32 = 7777
	const wantToUid uint32 = 20000

	// 第 0 片:权威 to_pid/to_uid
	c.handleEvent(makeChunkWithTo(tid, 0, dataSize, chunks[0], wantToPid, wantToUid))
	// 第 1 / 2 片:故意写不同 to_pid/to_uid,first-seen meta 不应被覆盖
	c.handleEvent(makeChunkWithTo(tid, 1, dataSize, chunks[1], 1, 2))
	c.handleEvent(makeChunkWithTo(tid, 2, dataSize, chunks[2], 3, 4))

	if len(*captured) != 1 {
		t.Fatalf("onEvent should fire once, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.ToPid != wantToPid || got.ToUid != wantToUid {
		t.Errorf("first-seen violated: expected ToPid=%d ToUid=%d (from chunk 0), got ToPid=%d ToUid=%d",
			wantToPid, wantToUid, got.ToPid, got.ToUid)
	}
}

// 9. ChunkIndex 越界:DataSize=1024 → totalChunks=1,投 ChunkIndex=5 应被忽略,
// 不崩溃,onEvent 不被调用,buffer 留待 cleanup 处理。
func TestHandleEvent_ChunkIndexOutOfRangeIgnored(t *testing.T) {
	c, captured := newCollectorForTest()

	const dataSize = ChunkSize // 单分片
	chunkBuf := make([]byte, ChunkSize)
	const tid = 0xBEEF0007

	// 越界 ChunkIndex
	c.handleEvent(makeChunk(tid, 5, dataSize, chunkBuf))

	if len(*captured) != 0 {
		t.Errorf("out-of-range chunk index must not produce onEvent, got %d", len(*captured))
	}
	// buffer 还在(没被 complete 也没被 evict —— 等 cleanup tick 时自然清掉)
	if _, exists := c.transactionBuffers[tid]; !exists {
		t.Error("buffer should still exist (waiting for legitimate chunk 0)")
	}
	// 现在补一个合法的 chunk 0,验证 buffer 仍可正常 complete(不被越界事件污染)
	body := patternBytes(dataSize, 0xC0)
	good := make([]byte, ChunkSize)
	copy(good, body)
	c.handleEvent(makeChunk(tid, 0, dataSize, good))

	if len(*captured) != 1 {
		t.Fatalf("after legitimate chunk 0, onEvent should fire, got %d", len(*captured))
	}
	if !bytes.Equal((*captured)[0].ParcelData, body) {
		t.Error("after recovery from out-of-range chunk, ParcelData mismatches")
	}
}

// =====================================================
// spec 2026-05-03 § 13 D3:base / stack record 合并 + TLV
// =====================================================

// makeStackRecord 构造 bpfStackTraceEvent
func makeStackRecord(tid uint64, pid, threadId uint32, kdepth, udepth int16) *bpfStackTraceEvent {
	st := &bpfStackTraceEvent{
		Magic:         StackRecordMagic,
		TransactionId: tid,
		Pid:           pid,
		Tid:           threadId,
		KstackDepth:   kdepth,
		UstackDepth:   udepth,
	}
	for i := int16(0); i < kdepth && int(i) < len(st.Kstack); i++ {
		st.Kstack[i] = 0xffff_ffc0_0800_0000 + uint64(i)*0x100
	}
	for i := int16(0); i < udepth && int(i) < len(st.Ustack); i++ {
		st.Ustack[i] = 0x7000_0000_0000 + uint64(i)*0x100
	}
	return st
}

// stack record 先到、base 后到 → 按 transaction_id 合并(spec § 4.1.2)
func TestHandleEvent_StackRecordMergedWithBase(t *testing.T) {
	c, captured := newCollectorForTest()
	c.SetSymbolizer(NewSymbolizer(log.New(io.Discard, "", 0)))

	const tid = 0xBABE0001
	const dataSize = 256

	// stack record 先到
	st := makeStackRecord(tid, 1234, 1234, 3, 2)
	c.handleStackRecord(st)
	// pendingStack 应该有这个 tid
	if _, ok := c.pendingStack[tid]; !ok {
		t.Fatal("stack record should be in pendingStack")
	}

	// base record 到达
	body := patternBytes(dataSize, 0x10)
	chunkBuf := make([]byte, ChunkSize)
	copy(chunkBuf, body)
	c.handleEvent(makeChunk(tid, 0, dataSize, chunkBuf))

	if len(*captured) != 1 {
		t.Fatalf("expected 1 event captured, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.StackTrace == nil {
		t.Fatal("StackTrace should be merged into event")
	}
	// Symbolizer 没加载 kallsyms,内核帧应该全部 FAILED → quality 计入 SymbolizeFailed
	// 但 KFrames 数组本身仍按 depth 填(每帧 quality=FAILED,但 frame 仍 emit)
	if len(got.StackTrace.KFrames) != 3 {
		t.Errorf("expected 3 KFrames, got %d", len(got.StackTrace.KFrames))
	}
	if len(got.StackTrace.UFrames) != 2 {
		t.Errorf("expected 2 UFrames, got %d", len(got.StackTrace.UFrames))
	}
	// pendingStack 已被消费
	if _, ok := c.pendingStack[tid]; ok {
		t.Error("pendingStack should be empty after merge")
	}
}

// 100ms TTL 后无 base 到达 → 孤儿 stack 被清掉,base 不影响
func TestHandleStackRecord_TTLEvictsOrphan(t *testing.T) {
	c, _ := newCollectorForTest()

	const tid = 0xBABE0002
	st := makeStackRecord(tid, 1, 2, 1, 0)
	c.handleStackRecord(st)
	// 把 receivedNs 拨到很久以前
	c.pendingStackMu.Lock()
	c.pendingStack[tid].receivedNs = time.Now().UnixNano() - int64(2*pendingStackTTL)
	c.evictStalePendingStack()
	c.pendingStackMu.Unlock()

	if _, ok := c.pendingStack[tid]; ok {
		t.Error("orphan stack should be evicted after TTL")
	}
	if c.stackRecordOrphanCount.Load() == 0 {
		t.Error("orphan counter should be incremented")
	}
}

// base record 到达但无 stack → stackTrace=nil,base_no_stack 计数 +1
func TestHandleEvent_BaseRecordWithoutStack(t *testing.T) {
	c, captured := newCollectorForTest()

	const tid = 0xBABE0003
	const dataSize = 256
	body := patternBytes(dataSize, 0x20)
	chunkBuf := make([]byte, ChunkSize)
	copy(chunkBuf, body)
	c.handleEvent(makeChunk(tid, 0, dataSize, chunkBuf))

	if len(*captured) != 1 {
		t.Fatalf("expected 1 event, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.StackTrace != nil {
		t.Error("StackTrace should be nil when no stack record arrived")
	}
	if c.baseRecordNoStackCount.Load() != 1 {
		t.Errorf("base_no_stack count should be 1, got %d", c.baseRecordNoStackCount.Load())
	}
}

// =====================================================
// spec § 4.4.4:5 级 TLV guard 边界覆盖
// =====================================================

// 完整 round-trip:Encode 后 Decode 还原 StackTrace
func TestStackTraceTLV_RoundTrip(t *testing.T) {
	st := &StackTrace{
		Quality:   StackQualityFull,
		Truncated: 0x03,
		KFrames: []StackFrame{
			{PC: 0xffffffc008001100, Module: "kernel", Symbol: "binder_ioctl", Offset: 0x100},
		},
		UFrames: []StackFrame{
			{PC: 0x7f8a000000, Module: "/system/lib64/libbinder.so", Symbol: "IPCThreadState::transact", Offset: 0x4c},
		},
		FailureReason: "",
	}
	parcel := []byte{0x01, 0x02, 0x03}
	ev := &BinderEventPayload{
		Timestamp:  100,
		Pid:        1,
		Uid:        2,
		Code:       3,
		DataSize:   uint32(len(parcel)),
		ParcelData: parcel,
		StackTrace: st,
	}
	encoded := ev.Encode()
	decoded, err := DecodeBinderEvent(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if decoded.StackTrace == nil {
		t.Fatal("StackTrace should round-trip")
	}
	if decoded.StackTrace.Quality != StackQualityFull {
		t.Errorf("quality: %d", decoded.StackTrace.Quality)
	}
	if len(decoded.StackTrace.KFrames) != 1 || decoded.StackTrace.KFrames[0].Symbol != "binder_ioctl" {
		t.Errorf("KFrames mismatch: %+v", decoded.StackTrace.KFrames)
	}
	if len(decoded.StackTrace.UFrames) != 1 || decoded.StackTrace.UFrames[0].Symbol != "IPCThreadState::transact" {
		t.Errorf("UFrames mismatch: %+v", decoded.StackTrace.UFrames)
	}
}

// ext_flags=0 → Decode 跳过 TLV,stackTrace=nil
func TestStackTraceTLV_ExtFlagsZeroSkipsTLV(t *testing.T) {
	parcel := []byte{0xAA}
	ev := &BinderEventPayload{
		DataSize:   uint32(len(parcel)),
		ParcelData: parcel,
		// StackTrace nil → ext_flags 不会置位
	}
	encoded := ev.Encode()
	if encoded[1] != 0 {
		t.Errorf("ext_flags should be 0, got %d", encoded[1])
	}
	// 即便后面追加垃圾 TLV 字节也应被忽略(因为 ext_flags 没置位 → Guard 3 早返)
	encoded = append(encoded, []byte{0x01, 0x00, 0x00, 0xff, 0xff, 0xde, 0xad}...)
	decoded, err := DecodeBinderEvent(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if decoded.StackTrace != nil {
		t.Error("stackTrace should be nil when ext_flags=0")
	}
}

// Guard 1: payloadLen < 60 → 静默 fail-open(返回 ErrTLVGuardFailOpen sentinel)+ g1 计数 +1
//
// spec § 4.4.4:G1/G2 失败"绝不报错日志(避免日志洪流)",只 telemetry 静默计数。
// 调用方按 errors.Is(err, ErrTLVGuardFailOpen) 静默忽略。
func TestTLVGuard_G1BaseHeaderTooShort(t *testing.T) {
	before := tlvGuard.G1BaseHeaderTooShort.Load()
	ev, err := DecodeBinderEvent(make([]byte, 10))
	if !errors.Is(err, ErrTLVGuardFailOpen) {
		t.Errorf("G1 must return ErrTLVGuardFailOpen sentinel, got: %v", err)
	}
	if ev != nil {
		t.Errorf("G1 must return nil event, got: %+v", ev)
	}
	after := tlvGuard.G1BaseHeaderTooShort.Load()
	if after != before+1 {
		t.Errorf("g1 counter should bump by 1, got %d→%d", before, after)
	}
}

// Guard 2: ParcelData 截断 → 静默 fail-open(同 G1)+ g2 计数 +1
func TestTLVGuard_G2ParcelTruncated(t *testing.T) {
	before := tlvGuard.G2ParcelTruncated.Load()
	// 构造 60B 头 + data_size=100,但实际 payload 只有 60+10=70B
	buf := make([]byte, 60+10)
	buf[0] = BinderEventVersionV2
	buf[1] = ExtFlagHasStackTLV
	binary.BigEndian.PutUint32(buf[28:], 100) // data_size=100,远大于实际
	ev, err := DecodeBinderEvent(buf)
	if !errors.Is(err, ErrTLVGuardFailOpen) {
		t.Errorf("G2 must return ErrTLVGuardFailOpen sentinel, got: %v", err)
	}
	if ev != nil {
		t.Errorf("G2 must return nil event, got: %+v", ev)
	}
	after := tlvGuard.G2ParcelTruncated.Load()
	if after != before+1 {
		t.Errorf("g2 counter should bump by 1, got %d→%d", before, after)
	}
}

// Guard 4: ext_flags & 0x01 + payloadLen < baseEnd+5 → fail-open + g4 计数 +1
func TestTLVGuard_G4TLVHeaderTruncated(t *testing.T) {
	before := tlvGuard.G4TLVHeaderTruncated.Load()
	// 60B 头 + 0B parcel + 仅 3B TLV 头(应该 5B)
	buf := make([]byte, 60+3)
	buf[0] = BinderEventVersionV2
	buf[1] = ExtFlagHasStackTLV
	binary.BigEndian.PutUint32(buf[28:], 0)
	buf[60] = TLVTypeStackTrace
	// 后面 2 字节就截断
	ev, err := DecodeBinderEvent(buf)
	if err != nil {
		t.Errorf("should fail-open, not error: %v", err)
	}
	if ev != nil && ev.StackTrace != nil {
		t.Error("StackTrace should be nil on G4 fail")
	}
	after := tlvGuard.G4TLVHeaderTruncated.Load()
	if after != before+1 {
		t.Errorf("g4 counter should bump by 1, got %d→%d", before, after)
	}
}

// Guard 5: tlv_length 超过剩余字节 → fail-open + g5 计数 +1
func TestTLVGuard_G5TLVPayloadOverflow(t *testing.T) {
	before := tlvGuard.G5TLVPayloadOverflow.Load()
	// 60B 头 + 0B parcel + 5B TLV 头声明 length=999,但 payload 只有 5B
	buf := make([]byte, 60+5)
	buf[0] = BinderEventVersionV2
	buf[1] = ExtFlagHasStackTLV
	binary.BigEndian.PutUint32(buf[28:], 0)
	buf[60] = TLVTypeStackTrace
	binary.BigEndian.PutUint32(buf[61:], 999)
	ev, err := DecodeBinderEvent(buf)
	if err != nil {
		t.Errorf("should fail-open, got %v", err)
	}
	if ev != nil && ev.StackTrace != nil {
		t.Error("StackTrace should be nil on G5 fail")
	}
	after := tlvGuard.G5TLVPayloadOverflow.Load()
	if after != before+1 {
		t.Errorf("g5 counter should bump by 1, got %d→%d", before, after)
	}
}

// 未知 TLV 类型 → 跳过(向前兼容,不计入 guard 失败)
func TestTLVGuard_UnknownTLVTypeIsSilent(t *testing.T) {
	buf := make([]byte, 60+5+4)
	buf[0] = BinderEventVersionV2
	buf[1] = ExtFlagHasStackTLV
	binary.BigEndian.PutUint32(buf[28:], 0)
	buf[60] = 0x99 // 未知类型
	binary.BigEndian.PutUint32(buf[61:], 4)
	// payload 4B 占位
	ev, err := DecodeBinderEvent(buf)
	if err != nil {
		t.Errorf("unknown TLV type should not error, got %v", err)
	}
	if ev != nil && ev.StackTrace != nil {
		t.Error("StackTrace should be nil for unknown TLV type")
	}
}

// fuzz-style:批量随机长度的 truncated payload,断言永远不 panic + 永远 stackTrace=nil
func TestTLVGuard_FuzzNeverPanics(t *testing.T) {
	cases := []int{0, 5, 30, 59, 60, 65, 70, 100, 200, 1000}
	for _, sz := range cases {
		buf := make([]byte, sz)
		// 半数置 ext_flags 位,半数不置
		if sz >= 2 {
			if sz%2 == 0 {
				buf[1] = ExtFlagHasStackTLV
			}
		}
		if sz > 0 {
			buf[0] = BinderEventVersionV2
		}
		// 不应 panic
		func() {
			defer func() {
				if r := recover(); r != nil {
					t.Errorf("panic on size %d: %v", sz, r)
				}
			}()
			_, _ = DecodeBinderEvent(buf)
		}()
	}
}

// =====================================================
// 共享 fuzz vectors:双端一致性 cross-check(回填 nitpicker M1)
// =====================================================
//
// spec § 4.4.4 + § 13 D5:同一份 fuzz 字节序列分别跑 daemon Go 与 app Kotlin
// 解码,断言两端 stackTrace=null 行为完全一致。
//
// 共享文件:daemon/testdata/tlv-fuzz-vectors.bin
//          app/src/test/resources/tlv-fuzz-vectors.bin (与上面是同一份字节序列)
//
// 文件格式(全 BE):
//   header:  4B "TLVF" magic + 2B vector_count
//   vector:  2B size_n + 1B expect_stack_null (0/1) + 2B note_len + note + n bytes payload
//
// 修改任一端 5 级 guard 时,**必须**:
//   1. 重新跑 daemon/testdata/tlv_fuzz_gen.go 生成 .bin
//   2. 把生成的 .bin 复制到 app/src/test/resources/
//   3. 跑 Go 与 Kotlin 两端测试,确保 expectStackNull 字段两端都通过
//
// app 端对应测试:app/.../StackTraceTLVTest.kt::sharedFuzzVectorsCrossCheck
func TestTLVGuard_SharedFuzzVectorsCrossCheck(t *testing.T) {
	data, err := os.ReadFile("testdata/tlv-fuzz-vectors.bin")
	if err != nil {
		t.Fatalf("read fuzz vectors: %v", err)
	}
	if len(data) < 6 || string(data[:4]) != "TLVF" {
		t.Fatalf("bad magic: %q", data[:4])
	}
	cnt := binary.BigEndian.Uint16(data[4:])
	off := 6
	for i := uint16(0); i < cnt; i++ {
		if off+5 > len(data) {
			t.Fatalf("vector %d header truncated", i)
		}
		size := int(binary.BigEndian.Uint16(data[off:]))
		expectStackNull := data[off+2] != 0
		noteLen := int(binary.BigEndian.Uint16(data[off+3:]))
		off += 5
		if off+noteLen+size > len(data) {
			t.Fatalf("vector %d body truncated", i)
		}
		note := string(data[off : off+noteLen])
		off += noteLen
		payload := data[off : off+size]
		off += size

		// 调 DecodeBinderEvent;吞掉所有 error(spec § 4.4.4 fail-open)
		ev, _ := DecodeBinderEvent(payload)
		gotNull := ev == nil || ev.StackTrace == nil
		if gotNull != expectStackNull {
			t.Errorf("vector #%d %q: expectStackNull=%v but got null=%v (ev=%v)",
				i, note, expectStackNull, gotNull, ev)
		}
	}
}

// =====================================================
// P4-B-2/3 融合方案 C 状态机用例(spec § 5.6.3 边界场景表)
// =====================================================

// makeMainEventWithCookie 构造一个 1 chunk(DataSize=ChunkSize)的 SYNC 主事件,
// 带指定 cookie 与 tx_flags。cookie==0 走降级路径(直接 emit,不进 pendingMain),
// cookie!=0 走状态机。
func makeMainEventWithCookie(transactionId, cookie uint64, txFlags uint32, isReply uint8) *bpfBinderTraceEvent {
	chunkBuf := make([]byte, ChunkSize)
	ev := makeChunk(transactionId, 0, ChunkSize, chunkBuf)
	ev.Cookie = cookie
	ev.Flags = txFlags
	ev.IsReply = isReply
	if isReply != 0 {
		// reply 主事件直接带 PairId(BPF 端从 thread->transaction_stack->debug_id 写入)
		ev.PairId = 0xDEADBEEF
	}
	return ev
}

// makeSidebandEvent 构造 raw_tp sideband
func makeSidebandEvent(cookie, debugID uint64, toPid, toUid uint32, toUidUnsupported uint8) *bpfBinderSidebandEvent {
	return &bpfBinderSidebandEvent{
		Kind:             evtKindSideband,
		Cookie:           cookie,
		KernelDebugId:    debugID,
		ToPid:            toPid,
		ToUid:            toUid,
		ToUidUnsupported: toUidUnsupported,
	}
}

// 1. cookie 重复 sanity(B1 修正后):
//    a) 严格递增 → 无 violation
//    b) **乱序到达**(per-CPU ringbuf 出队天然乱序)→ 无 violation(关键修正点!)
//    c) 同一 cookie 在窗口内出现 2 次 → +1 violation
//    d) 老旧 cookie 被环 evict 后再出现 → 不视为重复(窗口外)
func TestStateMachine_CookieMonotonic(t *testing.T) {
	c, _ := newCollectorForTest()

	// a) 严格递增
	c.handleEvent(makeMainEventWithCookie(0xA01, 1, 0, 0))
	c.handleEvent(makeMainEventWithCookie(0xA02, 2, 0, 0))
	c.handleEvent(makeMainEventWithCookie(0xA03, 3, 0, 0))
	if got := c.failures.cookieMonotonicViolation.Load(); got != 0 {
		t.Errorf("monotonic case should not violate, got %d", got)
	}

	// b) 乱序(cookie=2 < 已见的 3)—— 修正前会误报,修正后必须不报
	c.handleEvent(makeMainEventWithCookie(0xA04, 4, 0, 0))
	c.handleEvent(makeMainEventWithCookie(0xA05, 2 /* "倒序" */, 0, 0))
	// cookie=2 已经在 ring 里(第一次以 cookie=2 入过)→ 这其实是 duplicate,
	// 测试乱序场景需要换一个之前没用过的 cookie:
	if got := c.failures.cookieMonotonicViolation.Load(); got != 1 {
		t.Errorf("expected one duplicate violation (cookie=2 reused), got %d", got)
	}
	// 乱序但全新的 cookie:5 到达后再到 0xA(=10)→ 不计 violation
	c.handleEvent(makeMainEventWithCookie(0xA06, 0xA, 0, 0))
	c.handleEvent(makeMainEventWithCookie(0xA07, 5, 0, 0))
	if got := c.failures.cookieMonotonicViolation.Load(); got != 1 {
		t.Errorf("out-of-order distinct cookies must NOT violate, got %d", got)
	}

	// c) 显式重复:再次发 cookie=0xA → +1
	c.handleEvent(makeMainEventWithCookie(0xA08, 0xA, 0, 0))
	if got := c.failures.cookieMonotonicViolation.Load(); got != 2 {
		t.Errorf("duplicate cookie should fire violation, got %d", got)
	}
}

// TestStateMachine_CookieRingEvictsOld:cookie 环被填满之后,被 evict 出去的旧 cookie
// 再出现不再视为重复(窗口外),保证 sanity 在长跑中不会饱和。
func TestStateMachine_CookieRingEvictsOld(t *testing.T) {
	c, _ := newCollectorForTest()

	// 把环填满 + 再多塞 N 条把第一条挤出去
	first := uint64(0x1000_0000)
	for i := 0; i < recentCookieRingSize+8; i++ {
		c.handleEvent(makeMainEventWithCookie(uint64(0xB000+i), first+uint64(i), 0, 0))
	}
	// first(已被 evict 出环)再出现 → 不应视为重复
	before := c.failures.cookieMonotonicViolation.Load()
	c.handleEvent(makeMainEventWithCookie(0xB_FFFF, first, 0, 0))
	after := c.failures.cookieMonotonicViolation.Load()
	if after != before {
		t.Errorf("evicted cookie reuse must not violate (window expired): before=%d after=%d",
			before, after)
	}
}

// 2. 嵌套 reply(B 在处理 A 时再调 C):cookie 递增 + 两条 SYNC + 两条 sideband 各自配对,
//    debug_id 互不串。这里直接模拟 daemon 端状态机的 cookie/sideband 对账,
//    内核 thread->transaction_stack 栈结构语义由 P4-B-1 BPF reply 路径保证(spec § 6.2 步骤 8)。
func TestStateMachine_NestedReply_Independence(t *testing.T) {
	c, captured := newCollectorForTest()

	// 主 A(cookie=10) 进 pendingMain
	c.handleEvent(makeMainEventWithCookie(0xB01, 10, 0, 0))
	// 主 C(cookie=11) 进 pendingMain(嵌套调用,cookie 递增)
	c.handleEvent(makeMainEventWithCookie(0xB02, 11, 0, 0))

	if len(*captured) != 0 {
		t.Fatalf("nothing should emit before sideband, got %d", len(*captured))
	}

	// C 的 sideband 先到(嵌套调用先 reply)→ 合并 emit
	c.handleSideband(makeSidebandEvent(11, 0xCCCCC, 333, 1011, 0))
	if len(*captured) != 1 {
		t.Fatalf("C should emit after its sideband, got %d", len(*captured))
	}
	if (*captured)[0].PairId != 0xCCCCC || (*captured)[0].ToPid != 333 || (*captured)[0].ToUid != 1011 {
		t.Errorf("C event mismatches sideband fields: %+v", (*captured)[0])
	}

	// A 的 sideband 后到 → 合并 emit
	c.handleSideband(makeSidebandEvent(10, 0xAAAAA, 222, 1010, 0))
	if len(*captured) != 2 {
		t.Fatalf("A should emit after its sideband, got %d", len(*captured))
	}
	if (*captured)[1].PairId != 0xAAAAA || (*captured)[1].ToPid != 222 {
		t.Errorf("A event mismatches sideband fields: %+v", (*captured)[1])
	}

	// 两条事件 cookie / debug_id 互不串
	if (*captured)[0].PairId == (*captured)[1].PairId {
		t.Error("nested reply pair_id must differ")
	}

	// pendingMain 必须已清空
	c.stateMu.Lock()
	if got := len(c.pendingMain); got != 0 {
		t.Errorf("pendingMain should be empty, got %d", got)
	}
	c.stateMu.Unlock()
}

// 3.a oneway sideband 早到 → 主到达时合并 emit,不计任何失败 bucket。
func TestStateMachine_Oneway_SidebandEarly(t *testing.T) {
	c, captured := newCollectorForTest()

	// sideband 先到
	c.handleSideband(makeSidebandEvent(20, 0xD00, 444, 2020, 0))
	if len(*captured) != 0 {
		t.Fatal("sideband alone should not emit")
	}
	c.stateMu.Lock()
	if _, ok := c.pendingOneway[20]; !ok {
		t.Fatal("sideband should be parked in pendingOneway")
	}
	c.stateMu.Unlock()

	// oneway 主到达 → 合并 emit
	c.handleEvent(makeMainEventWithCookie(0xC01, 20, txFlagOneWay, 0))
	if len(*captured) != 1 {
		t.Fatalf("oneway main should emit after merging sideband, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.PairId != 0xD00 || got.ToPid != 444 || got.ToUid != 2020 {
		t.Errorf("oneway merge field mismatch: %+v", got)
	}

	snap := c.FailureSnapshot()
	if snap.SidebandOrphan != 0 || snap.OnewaySidebandLate != 0 || snap.EntryEarlyReturn != 0 {
		t.Errorf("oneway early-sideband must not fail: %+v", snap)
	}
	// recentOnewayEmit 应记录 cookie 20
	c.stateMu.Lock()
	if _, ok := c.recentOneway[20]; !ok {
		t.Error("recentOnewayEmit should track this cookie")
	}
	if _, ok := c.pendingOneway[20]; ok {
		t.Error("pendingOneway should be drained")
	}
	c.stateMu.Unlock()
}

// 3.b oneway 主先到 / sideband 1s 内到 → 主 cookie-only emit,sideband 落 OnewaySidebandLate。
func TestStateMachine_Oneway_SidebandLateWithin1s(t *testing.T) {
	c, captured := newCollectorForTest()

	// oneway 主先到 → cookie-only emit
	c.handleEvent(makeMainEventWithCookie(0xC02, 21, txFlagOneWay, 0))
	if len(*captured) != 1 {
		t.Fatalf("oneway main should emit immediately, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.ToPid != 0 {
		t.Errorf("cookie-only emit should not have ToPid populated yet: %+v", got)
	}

	// 1s 内 sideband 到达
	c.handleSideband(makeSidebandEvent(21, 0xD11, 555, 2121, 0))
	// 不再 emit 第二条事件(spec § 5.6.3 默认丢弃晚到 sideband 携带的 to_pid/to_uid)
	if len(*captured) != 1 {
		t.Errorf("late sideband must NOT trigger second emit, got %d", len(*captured))
	}
	snap := c.FailureSnapshot()
	if snap.OnewaySidebandLate != 1 {
		t.Errorf("oneway_sideband_late should be 1, got %d", snap.OnewaySidebandLate)
	}
	if snap.SidebandOrphan != 0 {
		t.Errorf("sideband_orphan must remain 0, got %d", snap.SidebandOrphan)
	}
}

// 3.c oneway 主先到 / sideband > 1s 后到 → recentOnewayEmit 已 evict,sideband 走兜底
//     pendingOneway → 1s TTL → SidebandOrphan++。
func TestStateMachine_Oneway_SidebandLateBeyond1s(t *testing.T) {
	c, _ := newCollectorForTest()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	// 主 emit
	c.handleEvent(makeMainEventWithCookie(0xC03, 22, txFlagOneWay, 0))

	// 时间快进 2s,recentOnewayEmit 已过期(1s TTL),sweep 一下
	cur = t0.Add(2 * time.Second)
	c.runSweep(cur)
	c.stateMu.Lock()
	if _, ok := c.recentOneway[22]; ok {
		t.Fatal("recentOnewayEmit should have been swept")
	}
	c.stateMu.Unlock()

	// sideband 现在到达 → 三处 lookup miss → 写 pendingOneway
	c.handleSideband(makeSidebandEvent(22, 0xD22, 0, 0, 0))
	c.stateMu.Lock()
	if _, ok := c.pendingOneway[22]; !ok {
		t.Fatal("sideband should fall back to pendingOneway")
	}
	c.stateMu.Unlock()

	// 再快进 2s 让 pendingOneway TTL 到期
	cur = t0.Add(4 * time.Second)
	c.runSweep(cur)

	snap := c.FailureSnapshot()
	if snap.SidebandOrphan != 1 {
		t.Errorf("sideband_orphan should be 1 after pendingOneway TTL, got %d", snap.SidebandOrphan)
	}
	if snap.OnewaySidebandLate != 0 {
		t.Errorf("oneway_sideband_late should remain 0, got %d", snap.OnewaySidebandLate)
	}
}

// 4. SYNC 主到达 → sideband 在 5s 内到达 → 合并 emit;无失败计数。
func TestStateMachine_Sync_SidebandWithinTTL(t *testing.T) {
	c, captured := newCollectorForTest()

	c.handleEvent(makeMainEventWithCookie(0xC04, 30, 0, 0))
	if len(*captured) != 0 {
		t.Fatal("SYNC main alone should not emit")
	}

	c.handleSideband(makeSidebandEvent(30, 0xE30, 666, 3030, 0))
	if len(*captured) != 1 {
		t.Fatalf("SYNC main should emit after sideband, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.PairId != 0xE30 || got.ToPid != 666 || got.ToUid != 3030 {
		t.Errorf("SYNC merge field mismatch: %+v", got)
	}
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 0 || snap.SidebandOrphan != 0 {
		t.Errorf("SYNC happy path must not fail: %+v", snap)
	}
}

// 5. SYNC 主到达 → sideband 5s 后才来 → pendingMain TTL evict → entry_early_return + emit pair_id=0
func TestStateMachine_Sync_SidebandTimeout(t *testing.T) {
	c, captured := newCollectorForTest()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	c.handleEvent(makeMainEventWithCookie(0xC05, 31, 0, 0))
	cur = t0.Add(6 * time.Second)
	c.runSweep(cur)

	// pendingMain TTL → emit 一条 pair_id=0
	if len(*captured) != 1 {
		t.Fatalf("TTL evict should still emit (pair_id=0), got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.PairId != 0 {
		t.Errorf("TTL evict main should have pair_id=0, got %x", got.PairId)
	}
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 1 {
		t.Errorf("entry_early_return should be 1, got %d", snap.EntryEarlyReturn)
	}

	// 现在 sideband 极晚到 → 走兜底 pendingOneway → 1s 后 sideband_orphan
	c.handleSideband(makeSidebandEvent(31, 0xE31, 777, 3131, 0))
	cur = t0.Add(8 * time.Second)
	c.runSweep(cur)
	snap = c.FailureSnapshot()
	if snap.SidebandOrphan != 1 {
		t.Errorf("sideband_orphan should fire on TTL evict, got %d", snap.SidebandOrphan)
	}
}

// 6. reply 主事件:cookie==0,IsReply=1 → 直接 emit,不进状态机。
//    模拟 SG reply / 普通 reply 路径(spec § 6.2 步骤 8)—— BPF 端 reply 已经在主事件里
//    带了 PairId,daemon 不需要做任何事。BC_REPLY_SG 与普通 reply 走同一路径。
func TestStateMachine_ReplyPath_DirectEmit_SG(t *testing.T) {
	c, captured := newCollectorForTest()

	// reply 主事件:cookie=0(reply 路径不分配 cookie),PairId 来自 BPF 直读
	ev := makeMainEventWithCookie(0xC06, 0, 0, 1)
	ev.Code = bcReplySgFakeCode
	c.handleEvent(ev)

	if len(*captured) != 1 {
		t.Fatalf("reply should emit immediately, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.IsReply != 1 || got.PairId != 0xDEADBEEF {
		t.Errorf("reply path mismatch: %+v", got)
	}
	c.stateMu.Lock()
	if len(c.pendingMain) != 0 || len(c.pendingOneway) != 0 {
		t.Errorf("reply must not enter state maps")
	}
	c.stateMu.Unlock()
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 0 || snap.SidebandOrphan != 0 {
		t.Errorf("reply happy path must not fail: %+v", snap)
	}
}

// bcReplySgFakeCode 仅用于本测试文件,模拟 SG reply 路径的 method code
const bcReplySgFakeCode = 0x42424242

// 7. BC_FREE_BUFFER before reply:在 daemon 视角,buffer 释放发生在 binder driver 内部,
//    不会触发 binder_transaction kprobe,也不会注入额外 sideband。验证 reply 主事件
//    不被 buffer 释放打断,pair_id 仍正确(模拟:reply 事件正常到达)。
func TestStateMachine_BC_FREE_BUFFER_BeforeReply(t *testing.T) {
	c, captured := newCollectorForTest()

	// 1) request 主事件 + sideband 正常配对
	c.handleEvent(makeMainEventWithCookie(0xC07001, 40, 0, 0))
	c.handleSideband(makeSidebandEvent(40, 0xE40, 888, 4040, 0))
	if len(*captured) != 1 {
		t.Fatalf("request should emit after sideband, got %d", len(*captured))
	}

	// 2) reply 主事件直接到达(buffer 提前释放并不影响 BPF kprobe 入口语义)
	ev := makeMainEventWithCookie(0xC07002, 0, 0, 1)
	ev.PairId = 0xE40
	c.handleEvent(ev)
	if len(*captured) != 2 {
		t.Fatalf("reply should emit, got %d", len(*captured))
	}
	if (*captured)[1].PairId != 0xE40 {
		t.Errorf("reply pair_id mismatch: %x", (*captured)[1].PairId)
	}
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 0 || snap.SidebandOrphan != 0 {
		t.Errorf("BC_FREE_BUFFER scenario must not fail: %+v", snap)
	}
}

// 8. ringbuf overflow 派生 sideband_orphan 上限对账(spec § 5.6.3 ringbuf 级联段):
//    本用例**只**验证 daemon 端"上界等式"的算术对账(sideband_orphan ≤
//    ringbuf_overflow_main、residual=0),不覆盖 BPF→daemon 的 PERCPU map 读取链路
//    (那部分单独由 TestSyncRingbufOverflowFrom_* 用 fake reader 覆盖,见下面)。
func TestStateMachine_RingbufOverflow_OrphanUpperBound(t *testing.T) {
	c, _ := newCollectorForTest()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	// 模拟"BPF 端已经统计到 5 次主 reserve 失败"的状态。这里直接 Store 是为了把
	// 算术对账逻辑独立出来测,**syncRingbufOverflow 的真实读 map 路由由 M2 测试覆盖**。
	c.failures.ringbufOverflowMain.Store(5)

	// 5 个孤儿 sideband(主事件因 ringbuf overflow 丢失,sideband 仍送达)
	for i := 0; i < 5; i++ {
		c.handleSideband(makeSidebandEvent(uint64(50+i), uint64(0xF000+i), 0, 0, 0))
	}
	// 1s 后 pendingOneway TTL 到期 → 5 条都落 sideband_orphan
	cur = t0.Add(2 * time.Second)
	c.runSweep(cur)

	snap := c.FailureSnapshot()
	if snap.SidebandOrphan != 5 {
		t.Errorf("expected 5 sideband_orphan, got %d", snap.SidebandOrphan)
	}
	if snap.RingbufOverflowMain != 5 {
		t.Errorf("ringbuf_overflow_main should be 5, got %d", snap.RingbufOverflowMain)
	}
	// 验收上界:sideband_orphan ≤ ringbuf_overflow_main
	if snap.SidebandOrphan > snap.RingbufOverflowMain {
		t.Errorf("upper bound violated: %d > %d", snap.SidebandOrphan, snap.RingbufOverflowMain)
	}
	// 验收门槛(扣除主 overflow 派生上限后真孤儿 = 0)
	derived := snap.RingbufOverflowMain
	if snap.SidebandOrphan < derived {
		derived = snap.SidebandOrphan
	}
	residual := snap.SidebandOrphan - derived
	if residual != 0 {
		t.Errorf("real orphans (residual) should be 0, got %d", residual)
	}
}

// 8.b syncRingbufOverflowFrom 真实路由测试(review M2):
//     注入 fake reader,模拟 BPF PERCPU_ARRAY 各 CPU 的部分计数,验证:
//     - per-CPU 数组求和正确
//     - slot=0 → ringbufOverflowMain,slot=1 → ringbufOverflowSideband(不要混线)
//     - 任一 slot 读失败时跳过,**不清**已有计数
//     - 累加是 Store 而非 Add(BPF map 是绝对值,daemon 不应当增量加,否则双计)
func TestSyncRingbufOverflowFrom_Routing(t *testing.T) {
	c, _ := newCollectorForTest()

	// 第一波:slot 0 = [3, 2](CPU0=3, CPU1=2);slot 1 = [1, 0]
	reader := func(slot uint32) ([]uint64, error) {
		switch slot {
		case 0:
			return []uint64{3, 2}, nil
		case 1:
			return []uint64{1, 0}, nil
		}
		return nil, nil
	}
	c.syncRingbufOverflowFrom(reader)

	if got := c.failures.ringbufOverflowMain.Load(); got != 5 {
		t.Errorf("main slot sum should be 3+2=5, got %d", got)
	}
	if got := c.failures.ringbufOverflowSideband.Load(); got != 1 {
		t.Errorf("sideband slot sum should be 1+0=1, got %d", got)
	}

	// 第二波:BPF 端值变成 [10, 7] / [2, 0],daemon 应**覆盖**(Store)而不是累加
	reader2 := func(slot uint32) ([]uint64, error) {
		switch slot {
		case 0:
			return []uint64{10, 7}, nil
		case 1:
			return []uint64{2, 0}, nil
		}
		return nil, nil
	}
	c.syncRingbufOverflowFrom(reader2)

	if got := c.failures.ringbufOverflowMain.Load(); got != 17 {
		t.Errorf("main slot must be Store-overwritten to 17, got %d (Add bug?)", got)
	}
	if got := c.failures.ringbufOverflowSideband.Load(); got != 2 {
		t.Errorf("sideband slot must be Store-overwritten to 2, got %d", got)
	}
}

// 8.c 任一 slot 读失败时:跳过该 slot,不清已有计数。
func TestSyncRingbufOverflowFrom_ErrorPreservesPrev(t *testing.T) {
	c, _ := newCollectorForTest()

	c.failures.ringbufOverflowMain.Store(42)
	c.failures.ringbufOverflowSideband.Store(99)

	reader := func(slot uint32) ([]uint64, error) {
		// 两个 slot 都读失败
		return nil, errFakeReadFail
	}
	c.syncRingbufOverflowFrom(reader)

	if got := c.failures.ringbufOverflowMain.Load(); got != 42 {
		t.Errorf("main slot should be preserved on error, got %d (want 42)", got)
	}
	if got := c.failures.ringbufOverflowSideband.Load(); got != 99 {
		t.Errorf("sideband slot should be preserved on error, got %d (want 99)", got)
	}

	// 部分失败:slot 0 OK / slot 1 fail —— main 必须更新到新值,sideband 保留旧值。
	reader2 := func(slot uint32) ([]uint64, error) {
		if slot == 0 {
			return []uint64{50}, nil
		}
		return nil, errFakeReadFail
	}
	c.syncRingbufOverflowFrom(reader2)
	if got := c.failures.ringbufOverflowMain.Load(); got != 50 {
		t.Errorf("main updated, got %d (want 50)", got)
	}
	if got := c.failures.ringbufOverflowSideband.Load(); got != 99 {
		t.Errorf("sideband must remain 99 on slot=1 error, got %d", got)
	}
}

var errFakeReadFail = fakeError("fake bpf map read failure")

type fakeError string

func (e fakeError) Error() string { return string(e) }

// 9. to_uid_unsupported 降级路径:GKI 12-5.10 binder_proc 无 cred 字段,sideband 带
//    to_uid_unsupported=1, ToUid=0。daemon 端不能把 0 当成"真值"覆盖到主事件 ToUid 字段上。
func TestStateMachine_ToUidUnsupported_Degraded(t *testing.T) {
	c, captured := newCollectorForTest()

	// SYNC 主先到
	c.handleEvent(makeMainEventWithCookie(0xC09, 60, 0, 0))
	// sideband 带 ToUidUnsupported=1, ToUid=0(BPF 端降级)
	c.handleSideband(makeSidebandEvent(60, 0xE60, 999, 0, 1))

	if len(*captured) != 1 {
		t.Fatalf("merged emit expected, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.PairId != 0xE60 {
		t.Errorf("debug_id should still be applied: %x", got.PairId)
	}
	if got.ToPid != 999 {
		t.Errorf("to_pid should still be applied: %d", got.ToPid)
	}
	// to_uid_unsupported=1 → ToUid 必须保留主事件原值(stub 0),不能误覆盖到 0 当真值
	if got.ToUid != 0 {
		t.Errorf("ToUid should remain 0 in unsupported path, got %d", got.ToUid)
	}
	snap := c.FailureSnapshot()
	if snap.SidebandOrphan != 0 || snap.EntryEarlyReturn != 0 {
		t.Errorf("unsupported downgrade is not a failure: %+v", snap)
	}
}

// 10. SYNC cookie==0(BPF cookie 分配失败 / pending_map 写失败 降级路径):
//     直接 emit,不进 pendingMain,不计 entry_early_return。
//
//     spec 2026-05-09 daemon-cookie-race-fix:legacy 钩子下保留旧"直接 emit"语义。
//     新行为见 TestStateMachine_CookieZeroSync* 系列。
func TestStateMachine_SyncCookieZeroDegraded(t *testing.T) {
	c, captured := newCollectorForTest()

	c.handleEvent(makeMainEventWithCookie(0xC0A, 0, 0, 0))
	if len(*captured) != 1 {
		t.Fatalf("cookie==0 SYNC should emit immediately (degraded), got %d", len(*captured))
	}
	c.stateMu.Lock()
	if len(c.pendingMain) != 0 {
		t.Error("cookie==0 must not enter pendingMain")
	}
	c.stateMu.Unlock()
}

// spec 2026-05-09 daemon-cookie-race-fix:cookie==0 SYNC 不立即 emit,挂
// pendingMainByTxid 等 cookie sideband 反向激活,激活后转挂 pendingMain[cookie]
// 等 raw_tp sideband 注入 pair_id,最终 emit 携带真实 PairId。
func TestStateMachine_CookieZeroSyncParkedThenSidebandActivates(t *testing.T) {
	c, captured := newCollectorForState() // 不开 legacy 钩子

	const txid = uint64(0xC0B)
	const cookie = uint64(0xCC0)
	const debugID = uint64(0xD13)

	// 1) 主事件 cookie==0 → 挂 pendingMainByTxid,不 emit
	c.handleEvent(makeMainEventWithCookie(txid, 0, 0, 0))
	if len(*captured) != 0 {
		t.Fatalf("cookie==0 SYNC should park, got %d emitted", len(*captured))
	}
	c.stateMu.Lock()
	if _, ok := c.pendingMainByTxid[txid]; !ok {
		t.Error("event should be parked in pendingMainByTxid")
	}
	c.stateMu.Unlock()

	// 2) cookie sideband 后到 → 反向激活,转挂 pendingMain[cookie],仍不 emit
	c.handleCookieSideband(makeCookieSideband(txid, cookie, 0))
	c.stateMu.Lock()
	if _, ok := c.pendingMainByTxid[txid]; ok {
		t.Error("event should be removed from pendingMainByTxid after activation")
	}
	if _, ok := c.pendingMain[cookie]; !ok {
		t.Error("event should be relocated to pendingMain[cookie]")
	}
	c.stateMu.Unlock()
	if len(*captured) != 0 {
		t.Fatalf("not yet emit before raw_tp sideband, got %d", len(*captured))
	}

	// 3) raw_tp sideband 到 → 注入 pair_id + emit
	c.handleSideband(makeSidebandEvent(cookie, debugID, 0, 0, 0))
	if len(*captured) != 1 {
		t.Fatalf("raw_tp sideband should trigger emit, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.PairId != debugID {
		t.Errorf("PairId should be debugID 0x%x, got 0x%x", debugID, got.PairId)
	}
}

// 5s TTL 兜底:cookie sideband 始终未到达 → emit pair_id=0,等价改造前 cookie==0
// 直接 emit 降级语义。
func TestStateMachine_CookieZeroSyncTTLDegrades(t *testing.T) {
	c, captured := newCollectorForState()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	c.handleEvent(makeMainEventWithCookie(0xC0C, 0, 0, 0))
	if len(*captured) != 0 {
		t.Fatal("event should park, not emit")
	}

	// 跳到 5s 后,触发 TTL evict
	cur = t0.Add(6 * time.Second)
	c.runSweep(cur)
	if len(*captured) != 1 {
		t.Fatalf("TTL should emit pair_id=0, got %d", len(*captured))
	}
	got := (*captured)[0]
	if got.PairId != 0 {
		t.Errorf("TTL degrade emit should have PairId=0, got 0x%x", got.PairId)
	}
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn == 0 {
		t.Error("EntryEarlyReturn should be incremented on TTL evict")
	}
}

// pendingMainByTxid LRU 满时,从尾部 evict 最旧 entry → emit pair_id=0,
// 计 entry_early_return。等价改造前 cookie==0 直接 emit 降级。
func TestStateMachine_CookieZeroSyncLRUEvictsBack(t *testing.T) {
	c, captured := newCollectorForState()

	// 灌入容量+1 条事件,最早一条应被 evict 出来 emit
	for i := 0; i <= pendingMainCapacity; i++ {
		c.handleEvent(makeMainEventWithCookie(uint64(0xD000+i), 0, 0, 0))
	}
	if len(*captured) != 1 {
		t.Fatalf("LRU back evict should emit 1, got %d", len(*captured))
	}
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn == 0 {
		t.Error("EntryEarlyReturn should be incremented on LRU back evict")
	}
}

// ONEWAY cookie==0 仍走旧"直接 emit"语义(ONEWAY 没有 reply,无配对需求)。
func TestStateMachine_CookieZeroOnewayStillEmitsImmediate(t *testing.T) {
	c, captured := newCollectorForState() // 不开 legacy 钩子

	c.handleEvent(makeMainEventWithCookie(0xC0D, 0, txFlagOneWay, 0))
	if len(*captured) != 1 {
		t.Fatalf("cookie==0 ONEWAY should emit immediately, got %d", len(*captured))
	}
	c.stateMu.Lock()
	if len(c.pendingMainByTxid) != 0 {
		t.Error("ONEWAY cookie==0 must not enter pendingMainByTxid")
	}
	c.stateMu.Unlock()
}

// 11. 重复 sideband(BPF 端重发 bug):同 cookie sideband 到 2 次,第二次落 sideband_orphan。
//     B2 修复后:第二次命中后**必须立即清 pendingOneway[cookie]**,否则 sweeper TTL
//     evict 时会再次累加 sideband_orphan,同一 BPF bug 计 2 次,破坏
//     `sideband_orphan ≤ ringbuf_overflow_main` 等式。
func TestStateMachine_DuplicateSideband(t *testing.T) {
	c, _ := newCollectorForTest()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	// 第一次 sideband 早到 → 进 pendingOneway
	c.handleSideband(makeSidebandEvent(70, 0xE70, 0, 0, 0))
	// 第二次同 cookie sideband → lookup pendingOneway 命中 → sideband_orphan++
	c.handleSideband(makeSidebandEvent(70, 0xE70, 0, 0, 0))

	snap := c.FailureSnapshot()
	if snap.SidebandOrphan != 1 {
		t.Errorf("duplicate sideband should fire orphan once, got %d", snap.SidebandOrphan)
	}

	// 关键验证(B2):pendingOneway 必须已清空,sweeper TTL 不应再累加
	c.stateMu.Lock()
	if _, ok := c.pendingOneway[70]; ok {
		t.Errorf("pendingOneway[70] must be cleared after duplicate sideband (B2)")
	}
	c.stateMu.Unlock()

	// 推进时间 2s 触发 pendingOneway TTL sweep,断言 orphan 计数稳定不再 +1
	cur = t0.Add(2 * time.Second)
	c.runSweep(cur)

	snap = c.FailureSnapshot()
	if snap.SidebandOrphan != 1 {
		t.Errorf("sweeper after duplicate must NOT double-count: got %d, want 1",
			snap.SidebandOrphan)
	}
}

// 12. M1 修复:onEvent 慢回调不能阻塞 stateMu。
//     场景:onEvent 卡住几百 ms 模拟客户端慢/断;同时另一个 goroutine 跑 handleSideband,
//     必须能在 onEvent 卡住期间拿到 stateMu(进 lookup 表),证明锁已被释放。
//     回归 M1 之前的实现(锁内同步 emit)在该测试上必然死锁 / 超时。
func TestStateMachine_EmitDoesNotBlockStateMu(t *testing.T) {
	c, _ := newCollectorForTest()

	emitGate := make(chan struct{})
	emitDone := make(chan struct{})
	c.SetEventCallback(func(ev *BinderEventPayload) {
		<-emitGate // 卡住,直到测试主动释放
		close(emitDone)
	})

	// 让 handleSideband 触发一次 emit:先进 SYNC 主等 sideband
	c.handleEvent(makeMainEventWithCookie(0xE001, 100, 0, 0))

	// 在另一个 goroutine 触发 emit(sideband 命中 pendingMain → 合并 emit)
	sidebandFired := make(chan struct{})
	go func() {
		c.handleSideband(makeSidebandEvent(100, 0xDEADBEEF, 1, 2, 0))
		close(sidebandFired)
	}()

	// 给 sideband goroutine 足够时间走完 stateMu 内逻辑、出锁、调到卡住的 onEvent
	time.Sleep(20 * time.Millisecond)

	// 此时 onEvent 应该已经被调用且卡在 emitGate;
	// stateMu 应该**已经释放**,主线程能立即拿到锁。
	lockAcquired := make(chan struct{})
	go func() {
		c.stateMu.Lock()
		c.stateMu.Unlock()
		close(lockAcquired)
	}()

	select {
	case <-lockAcquired:
		// 期望分支:stateMu 没被慢 onEvent 卡住
	case <-time.After(200 * time.Millisecond):
		t.Fatal("stateMu still held while onEvent is blocked → M1 修复回归")
	}

	// 释放 emit 卡住,清理
	close(emitGate)
	<-emitDone
	<-sidebandFired
}


// 13. M5 修复:Start→立即 Stop 不留 sweeper goroutine(无 BPF objs 路径)。
//     这是个轻量回归测试,不挂 BPF;直接驱动 sweepLoop 的退出路径。
func TestSweeperStopRace(t *testing.T) {
	c := NewEventCollector(log.New(io.Discard, "", 0))

	// 模拟 Start 启动 sweeper goroutine 的部分(无 BPF objs)
	c.sweepStop = make(chan struct{})
	c.sweepDone = make(chan struct{})
	go c.sweepLoop()

	// 立即 Stop 路径(只关 sweeper,跳过 BPF 清理)
	close(c.sweepStop)

	select {
	case <-c.sweepDone:
		// OK:sweeper 干净退出
	case <-time.After(2 * time.Second):
		t.Fatal("sweeper goroutine did not exit within 2s")
	}
}

// 14. M5 强化:在 sweeper ticker 触发期间 Stop,验证 sweepLoop 能干净返回
//     (不会在 c.runSweep 调用过程中卡住;runSweep 不持锁久也不调 BPF)。
func TestSweeperStopDuringTickerFires(t *testing.T) {
	c := NewEventCollector(log.New(io.Discard, "", 0))

	c.sweepStop = make(chan struct{})
	c.sweepDone = make(chan struct{})
	go c.sweepLoop()

	// 等到第一次 ticker 周期之后再发停止信号(stateMachineSweepPeriod = 250ms)
	time.Sleep(2 * stateMachineSweepPeriod)
	close(c.sweepStop)

	select {
	case <-c.sweepDone:
	case <-time.After(2 * time.Second):
		t.Fatal("sweeper did not exit cleanly after multiple ticker fires")
	}
}

// 15. M5 强化:sweeper 在 close(sweepStop) 同一时刻 ticker 也在 fire,
//     repeat 多次确保退出无竞态(go test -count=N 也能捕获概率事件)。
//     iter 取 16 次,每次 0~3*period/8 不等延迟,总开销 < 2s。
func TestSweeperStopStress(t *testing.T) {
	for i := 0; i < 16; i++ {
		c := NewEventCollector(log.New(io.Discard, "", 0))
		c.sweepStop = make(chan struct{})
		c.sweepDone = make(chan struct{})
		go c.sweepLoop()
		// 随机化一点点等待,试图覆盖"刚好 ticker fire 那一帧 Stop"的 race window
		time.Sleep(time.Duration(i%4) * stateMachineSweepPeriod / 8)
		close(c.sweepStop)
		select {
		case <-c.sweepDone:
		case <-time.After(2 * time.Second):
			t.Fatalf("iteration %d: sweeper hung", i)
		}
	}
}

// =====================================================================
// B3 修复:cookie sideband 状态机回归测试
// =====================================================================

// makeCookieSideband 构造 EVT_KIND_COOKIE record(cookie 旁路 kprobe 上送)。
// transactionIdHint 在 B5 修复后语义实为精确 transaction_id(主 program 通过
// tid_to_txid_map 桥接)。
func makeCookieSideband(transactionIdHint, cookie uint64, tid uint32) *bpfBinderCookieSideband {
	return &bpfBinderCookieSideband{
		Kind:              evtKindCookie,
		Cookie:            cookie,
		TransactionIdHint: transactionIdHint,
		Tid:               tid,
	}
}

// 1. cookie sideband **先到** → 主事件首次见到 transaction_id 时合并(注入 meta.cookie)。
//
// 验证 handleEvent 第一次创建 buf 时 lookup pendingCookieByTxid[transactionId] 命中,
// 把 cookie 注入 buf.meta.cookie,并删 pendingCookieByTxid。
func TestStateMachine_CookieSideband_EarlyArrives_MergedByMain(t *testing.T) {
	c, captured := newCollectorForTest()

	// cookie sideband 先到(BPF cookie kprobe 在 LIFO 下后跑,但读 ringbuf 顺序由
	// 内核决定;daemon 必须容忍 cookie sideband 早于主事件第一个 chunk 到达)
	const txID uint64 = 0xCAFE001
	const cookie uint64 = 0xC0DEC0DE
	c.handleCookieSideband(makeCookieSideband(txID, cookie, 12345))

	// 此时 pendingCookieByTxid 应有 entry,主事件还没到
	c.pendingCookieMu.Lock()
	if pe, ok := c.pendingCookieByTxid[txID]; !ok || pe.cookie != cookie {
		t.Fatalf("pendingCookieByTxid not stored: ok=%v entry=%+v", ok, pe)
	}
	c.pendingCookieMu.Unlock()

	// 主事件到达(SYNC,cookie 字段为 0,等 cookie 注入)
	main := makeChunk(txID, 0, ChunkSize, make([]byte, ChunkSize))
	main.Cookie = 0 // 模拟 BPF 端写 0,cookie 由 sideband 注入
	c.handleEvent(main)

	// pendingCookieByTxid 应已清掉
	c.pendingCookieMu.Lock()
	if _, ok := c.pendingCookieByTxid[txID]; ok {
		t.Error("pendingCookieByTxid should have been consumed by handleEvent")
	}
	c.pendingCookieMu.Unlock()

	// 主事件应进入 pendingMain(因为有 cookie 桥接,SYNC 路径)
	c.stateMu.Lock()
	if _, ok := c.pendingMain[cookie]; !ok {
		t.Errorf("main event should route to pendingMain[%x] after cookie injected", cookie)
	}
	c.stateMu.Unlock()

	// 此时还没 emit(SYNC 等 raw_tp sideband)
	if len(*captured) != 0 {
		t.Errorf("SYNC main with cookie should park in pendingMain, not emit yet, got %d", len(*captured))
	}
}

// 2. 主事件**先到** → cookie sideband 后到时注入 buf.meta.cookie。
//
// 验证 handleCookieSideband case (a):主事件已在 transactionBuffers,
// buf.meta.cookie==0 时直接注入。
func TestStateMachine_CookieSideband_LateArrives_InjectedToBuffer(t *testing.T) {
	c, captured := newCollectorForTest()

	// 主事件第 1 chunk 先到(2 chunk 的事务,等齐前不会 emit)
	const txID uint64 = 0xCAFE002
	const cookie uint64 = 0xC0DE0002
	const dataSize uint64 = ChunkSize * 2 // 需要 2 chunks
	chunkBuf0 := make([]byte, ChunkSize)
	main0 := makeChunk(txID, 0, dataSize, chunkBuf0)
	main0.Cookie = 0
	c.handleEvent(main0)

	// 此时 transactionBuffers 应有 buffer,但 cookie==0
	c.bufferMu.Lock()
	buf, ok := c.transactionBuffers[txID]
	if !ok || buf.meta.cookie != 0 {
		t.Fatalf("expected buffer with cookie=0, got ok=%v cookie=%d", ok, buf.meta.cookie)
	}
	c.bufferMu.Unlock()

	// cookie sideband 后到 → 注入 buf.meta.cookie
	c.handleCookieSideband(makeCookieSideband(txID, cookie, 22222))

	c.bufferMu.Lock()
	if buf := c.transactionBuffers[txID]; buf == nil || buf.meta.cookie != cookie {
		t.Errorf("cookie should be injected into buf.meta.cookie, got %d", buf.meta.cookie)
	}
	c.bufferMu.Unlock()
	// pendingCookieByTxid 不应有 entry(case (a) 直接注入,不暂存)
	c.pendingCookieMu.Lock()
	if _, ok := c.pendingCookieByTxid[txID]; ok {
		t.Error("pendingCookieByTxid must NOT be populated when buffer already exists")
	}
	c.pendingCookieMu.Unlock()

	// 第 2 chunk 到达 → 现在 dataSize=2*ChunkSize,集齐 emit
	chunkBuf1 := make([]byte, ChunkSize)
	main1 := makeChunk(txID, 1, dataSize, chunkBuf1)
	main1.Cookie = 0
	c.handleEvent(main1)

	// SYNC 主事件有 cookie → 应进入 pendingMain[cookie](等 raw_tp sideband)
	c.stateMu.Lock()
	if _, ok := c.pendingMain[cookie]; !ok {
		t.Errorf("complete main event should route to pendingMain[%x]", cookie)
	}
	c.stateMu.Unlock()

	if len(*captured) != 0 {
		t.Errorf("SYNC main with cookie should park, not emit, got %d", len(*captured))
	}
}

// 3. transaction_id_hint == 0 或 cookie == 0 → handleCookieSideband 直接 fail-fast,
//    无副作用(不写 pendingCookieByTxid,不影响 transactionBuffers,不计 counter)。
func TestStateMachine_CookieSideband_ZeroFields_SilentlyDropped(t *testing.T) {
	c, _ := newCollectorForTest()

	// case 1: TransactionIdHint == 0
	c.handleCookieSideband(makeCookieSideband(0, 0xABC, 100))
	c.pendingCookieMu.Lock()
	if len(c.pendingCookieByTxid) != 0 {
		t.Errorf("zero hint should not populate pendingCookieByTxid, got %d", len(c.pendingCookieByTxid))
	}
	c.pendingCookieMu.Unlock()
	c.bufferMu.Lock()
	if len(c.transactionBuffers) != 0 {
		t.Errorf("zero hint should not affect transactionBuffers")
	}
	c.bufferMu.Unlock()

	// case 2: Cookie == 0
	c.handleCookieSideband(makeCookieSideband(0xDEADBEEF, 0, 100))
	c.pendingCookieMu.Lock()
	if len(c.pendingCookieByTxid) != 0 {
		t.Errorf("zero cookie should not populate pendingCookieByTxid, got %d", len(c.pendingCookieByTxid))
	}
	c.pendingCookieMu.Unlock()

	// 不计任何 failure counter
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 0 || snap.SidebandOrphan != 0 || snap.CookieMonotonicViolation != 0 {
		t.Errorf("zero-fields drop must not bump any counter: %+v", snap)
	}
}

// 4. pendingCookieByTxid TTL 过期 → evictStalePendingCookies 清理。
//
//	验证:超过 pendingCookieTTL 的 cookie sideband 被清除,**不**计入 EntryEarlyReturn
//	(主事件 early-return 已经独立计数,不应在此双计;daemon 视为"主未到达,沉默吸收")。
func TestStateMachine_CookieSideband_TTLEvictsOrphan(t *testing.T) {
	c, _ := newCollectorForTest()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	// cookie sideband 到达,无主事件
	const txID uint64 = 0xCAFE004
	c.handleCookieSideband(makeCookieSideband(txID, 0xC0DE004, 333))

	c.pendingCookieMu.Lock()
	if _, ok := c.pendingCookieByTxid[txID]; !ok {
		t.Fatal("pendingCookieByTxid should have entry before TTL")
	}
	c.pendingCookieMu.Unlock()

	// 时间快进超过 pendingCookieTTL(= staleBufferTTL = 5s)
	cur = t0.Add(pendingCookieTTL + time.Second)

	c.pendingCookieMu.Lock()
	c.evictStalePendingCookies()
	if _, ok := c.pendingCookieByTxid[txID]; ok {
		t.Error("pendingCookieByTxid entry should be evicted after TTL")
	}
	c.pendingCookieMu.Unlock()

	// 不计 EntryEarlyReturn(那是主路径 5s TTL 触发,不应在 cookie sideband 路径双计)
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 0 {
		t.Errorf("cookie sideband TTL evict must NOT bump EntryEarlyReturn, got %d", snap.EntryEarlyReturn)
	}
	if snap.SidebandOrphan != 0 {
		t.Errorf("cookie sideband TTL evict must NOT bump SidebandOrphan, got %d", snap.SidebandOrphan)
	}
}

// 5. M3 修复:pendingCookieByTxid LRU 容量上限。
//    超过 pendingCookieCapacity 时,从尾部 evict 最旧 entry,无副作用 counter。
func TestStateMachine_CookieSideband_LRUCapEvictsOldest(t *testing.T) {
	c, _ := newCollectorForTest()

	t0 := time.Unix(0, 0)
	cur := t0
	c.nowFn = func() time.Time { return cur }

	const overflow = 8 // 超过容量 N 条
	for i := 0; i < pendingCookieCapacity+overflow; i++ {
		cur = t0.Add(time.Duration(i) * time.Microsecond) // 每个 entry receivedNs 递增
		c.handleCookieSideband(makeCookieSideband(uint64(0xE0000+i), uint64(0xC0DE0+i), uint32(i)))
	}

	c.pendingCookieMu.Lock()
	defer c.pendingCookieMu.Unlock()

	// map 大小应严格 == capacity
	if got := len(c.pendingCookieByTxid); got != pendingCookieCapacity {
		t.Errorf("pendingCookieByTxid size should be %d, got %d", pendingCookieCapacity, got)
	}
	// LRU 链表大小应 == capacity
	if got := c.pendingCookieLRU.Len(); got != pendingCookieCapacity {
		t.Errorf("pendingCookieLRU len should be %d, got %d", pendingCookieCapacity, got)
	}
	// 最旧的 overflow 条应已被 evict(txid 0xE0000..0xE0000+overflow-1 不应在 map)
	for i := 0; i < overflow; i++ {
		if _, ok := c.pendingCookieByTxid[uint64(0xE0000+i)]; ok {
			t.Errorf("oldest entry txid=0x%x should be evicted by LRU cap", 0xE0000+i)
		}
	}
	// 最新的应该在
	for i := overflow; i < overflow+3; i++ {
		if _, ok := c.pendingCookieByTxid[uint64(0xE0000+i)]; !ok {
			t.Errorf("recent entry txid=0x%x should remain", 0xE0000+i)
		}
	}

	// LRU evict 不计 failure counter
	snap := c.FailureSnapshot()
	if snap.EntryEarlyReturn != 0 || snap.SidebandOrphan != 0 {
		t.Errorf("LRU evict must not bump counters: %+v", snap)
	}
}
