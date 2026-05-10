package com.btrace.viewer.parser

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.decoders.DecodeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoverageStatsTest {

    // copy() 是 data class 浅拷贝,只会带过去构造参数;懒加载缓存 _interfaceName 不会复制,
    // 所以接口名必须在 copy 之后再 set,否则 event.interfaceName 会回退到字面量 "Unknown"。
    private fun event(id: Long, source: DecodeSource?, iface: String = "android.app.IFoo"): BinderEvent {
        val base = BinderEvent.createMock(
            interfaceName = "placeholder",
            methodName = "m",
            callerPackage = "test",
            uid = 100
        ).copy(id = id)
        base.interfaceName = iface
        base.decodeSource = source
        return base
    }

    @Test
    fun `onEventAdded counts each bucket`() {
        val stats = CoverageStats()
        stats.onEventAdded(event(1, DecodeSource.AIDL_Q))
        stats.onEventAdded(event(2, DecodeSource.AIDL_P))
        stats.onEventAdded(event(3, DecodeSource.AIDL_O))
        stats.onEventAdded(event(4, DecodeSource.HIDL_DESCRIPTOR))
        stats.onEventAdded(event(5, DecodeSource.REPLY))
        stats.onEventAdded(event(6, DecodeSource.SPECIAL_CODE))
        stats.onEventAdded(event(7, DecodeSource.TARGET_REF, iface = "target@0xABCD"))
        stats.onEventAdded(event(8, DecodeSource.RAW_ASCII, iface = "raw"))

        val snap = stats.snapshot()
        assertEquals(8L, snap.total)
        // AIDL Q+P+O = 3, HIDL = 1, REPLY = 1, SPECIAL = 1, UNKNOWN = TARGET_REF+RAW_ASCII = 2
        assertEquals(3L, snap.buckets[CoverageBucket.AIDL])
        assertEquals(1L, snap.buckets[CoverageBucket.HIDL])
        assertEquals(1L, snap.buckets[CoverageBucket.REPLY])
        assertEquals(1L, snap.buckets[CoverageBucket.SPECIAL])
        assertEquals(2L, snap.buckets[CoverageBucket.UNKNOWN])
        // resolved = 非 UNKNOWN 桶 + 有 decodeSource 的全部 = 6
        assertEquals(6L, snap.resolved)
        assertEquals(0.75, snap.resolveRate, 1e-9)
    }

    @Test
    fun `onEventEvicted decrements symmetrically`() {
        val stats = CoverageStats()
        val a = event(1, DecodeSource.AIDL_Q)
        val b = event(2, DecodeSource.HIDL_DESCRIPTOR)
        val c = event(3, DecodeSource.TARGET_REF, iface = "target@0x1")
        stats.onEventAdded(a)
        stats.onEventAdded(b)
        stats.onEventAdded(c)
        stats.onEventEvicted(a)
        stats.onEventEvicted(c)

        val snap = stats.snapshot()
        assertEquals(1L, snap.total)
        assertEquals(0L, snap.buckets[CoverageBucket.AIDL])
        assertEquals(1L, snap.buckets[CoverageBucket.HIDL])
        assertEquals(0L, snap.buckets[CoverageBucket.UNKNOWN])
        assertEquals(1L, snap.resolved)
    }

    @Test
    fun `null decodeSource counts as Unknown bucket and not resolved`() {
        val stats = CoverageStats()
        stats.onEventAdded(event(1, source = null))
        stats.onEventAdded(event(2, source = null))
        val snap = stats.snapshot()
        assertEquals(2L, snap.total)
        assertEquals(0L, snap.resolved)
        assertEquals(2L, snap.buckets[CoverageBucket.UNKNOWN])
        // 总数 > 0 但 resolved = 0 → 解析率 = 0
        assertEquals(0.0, snap.resolveRate, 1e-9)
    }

    @Test
    fun `empty stats does not crash on resolveRate`() {
        val stats = CoverageStats()
        val snap = stats.snapshot()
        assertEquals(0L, snap.total)
        assertEquals(0L, snap.resolved)
        assertEquals(0.0, snap.resolveRate, 1e-9)
        // EMPTY 也走同一份契约
        assertEquals(0.0, CoverageSnapshot.EMPTY.resolveRate, 1e-9)
    }

    @Test
    fun `concurrent add and evict converge to expected totals`() {
        val stats = CoverageStats()
        val pool = Executors.newFixedThreadPool(8)
        val perWorker = 5_000
        val workers = 8
        val latch = CountDownLatch(workers)

        // 一半 worker 只 add,一半 worker add 然后立刻 evict 同一个事件 → 期望 total = 4*perWorker
        repeat(workers) { w ->
            pool.execute {
                try {
                    val onlyAdd = w % 2 == 0
                    repeat(perWorker) { i ->
                        val id = (w.toLong() shl 32) or i.toLong()
                        val src = when (i % 5) {
                            0 -> DecodeSource.AIDL_Q
                            1 -> DecodeSource.HIDL_DESCRIPTOR
                            2 -> DecodeSource.REPLY
                            3 -> DecodeSource.SPECIAL_CODE
                            else -> DecodeSource.TARGET_REF
                        }
                        val e = event(id, src, iface = if (src == DecodeSource.TARGET_REF) "target@0x$id" else "android.app.IFoo")
                        stats.onEventAdded(e)
                        if (!onlyAdd) stats.onEventEvicted(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("workers timed out", latch.await(20, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        val snap = stats.snapshot()
        // 4 个 add-only worker 各贡献 perWorker 个事件
        val expected = 4L * perWorker
        assertEquals(expected, snap.total)
        // 各桶累计正好等于 total
        val bucketSum = snap.buckets.values.sum()
        assertEquals(expected, bucketSum)
        // resolved = 非 UNKNOWN 桶之和。每 5 个里 4 个非 UNKNOWN(AIDL/HIDL/REPLY/SPECIAL)
        assertEquals(expected * 4 / 5, snap.resolved)
        // resolveRate 落在 [0.79, 0.81]
        assertTrue(snap.resolveRate in 0.79..0.81)
        assertFalse(snap.resolveRate.isNaN())
    }
}
