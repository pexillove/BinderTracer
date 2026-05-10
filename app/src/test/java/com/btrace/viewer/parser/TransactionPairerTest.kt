package com.btrace.viewer.parser

import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * spec § 6.3.2 TransactionPairer 单测。
 *
 * 设定 targetUid = 10086(随便挑个稳定值)。除非用例特别说明,所有事件 uid = 10086,
 * toUid = 0(模拟 P4 未落值的 BPF),pairId 由用例赋值。
 */
class TransactionPairerTest {

    private val targetUid = 10086

    private fun makeRequest(
        pairId: Long,
        interfaceName: String = "android.app.IFoo",
        methodName: String = "doIt",
        uid: Int = targetUid,
        toUid: Int = 0,
        code: Int = 1,
    ): BinderEvent = BinderEvent(
        id = pairId,
        timestamp = 0L,
        pid = 1234,
        uid = uid,
        code = code,
        flags = 0,
        rawParcel = ByteArray(0),
        isReply = false,
        toUid = toUid,
        pairId = pairId,
    ).apply {
        this.interfaceName = interfaceName
        this.methodName = methodName
    }

    private fun makeReply(
        pairId: Long,
        uid: Int = targetUid,
        toUid: Int = 0,
        code: Int = 1,
    ): BinderEvent = BinderEvent(
        id = pairId * 1000 + 1,
        timestamp = 0L,
        pid = 1234,
        uid = uid,
        code = code,
        flags = 0,
        rawParcel = ByteArray(0),
        isReply = true,
        toUid = toUid,
        pairId = pairId,
    )

    @Test
    fun `case 1 - 正常配对 同向 client request 命中 reply`() {
        // 目标 App 是 sender(client),reply 由对端回来。模拟 P4 未落值:reply 的 toUid=0,
        // 走 tryMatchReply 的 "toUid 缺失兜底" 分支去 clientRequests 找。
        val pairer = TransactionPairer()
        val req = makeRequest(pairId = 1L, interfaceName = "X", methodName = "m")
        pairer.recordRequest(req, targetUid)

        // reply:sender 是对端(uid != target),toUid 缺失 → 兜底查 client 表
        val reply = makeReply(pairId = 1L, uid = 99999, toUid = 0)
        val result = pairer.tryMatchReply(reply, targetUid)

        assertNotNull("reply 应该命中 client 表", result)
        assertEquals("X", result!!.interfaceName)
        assertEquals("m", result.methodName)
    }

    @Test
    fun `case 1b - server 方向 P4 已落值场景`() {
        // 目标 App 是 server,toUid == targetUid;它的 reply 由它自己发出,sender 是它
        val pairer = TransactionPairer()
        val req = makeRequest(
            pairId = 7L,
            interfaceName = "android.app.IBar",
            methodName = "go",
            uid = 99999,             // 外部 client 发来
            toUid = targetUid,       // 目标 App 是 receiver
        )
        pairer.recordRequest(req, targetUid)
        assertEquals("应该落 server 表", 1, pairer.serverSize())
        assertEquals(0, pairer.clientSize())

        val reply = makeReply(pairId = 7L, uid = targetUid, toUid = 99999)
        val result = pairer.tryMatchReply(reply, targetUid)

        assertNotNull(result)
        assertEquals("android.app.IBar", result!!.interfaceName)
        assertEquals("go", result.methodName)
    }

    @Test
    fun `case 2 - 重复 pairId 后入覆盖前入`() {
        // 同一 pairId 二次入表:语义上"前一次配对没回来,新的请求复用了 ID"——后入覆盖,
        // 配对返回后入的接口/方法。
        val pairer = TransactionPairer()
        pairer.recordRequest(makeRequest(pairId = 2L, interfaceName = "OldI", methodName = "old"), targetUid)
        pairer.recordRequest(makeRequest(pairId = 2L, interfaceName = "NewI", methodName = "new"), targetUid)

        val reply = makeReply(pairId = 2L, uid = 99999, toUid = 0)
        val result = pairer.tryMatchReply(reply, targetUid)

        assertNotNull(result)
        assertEquals("后入应覆盖前入", "NewI", result!!.interfaceName)
        assertEquals("new", result.methodName)
    }

    @Test
    fun `case 3 - TTL 淘汰 注入时钟 t=0 入表 t=5001 配对未命中`() {
        var fakeNow = 0L
        val pairer = TransactionPairer(clockMs = { fakeNow })

        pairer.recordRequest(makeRequest(pairId = 3L), targetUid)
        // 边界:严格 > TTL_MS 才视作过期(== 不算)
        fakeNow = TransactionPairer.TTL_MS + 1L

        val reply = makeReply(pairId = 3L, uid = 99999)
        val result = pairer.tryMatchReply(reply, targetUid)

        assertNull("超过 5s TTL 后应配对失败", result)
    }

    @Test
    fun `case 3b - TTL 边界 t=5000 仍命中`() {
        var fakeNow = 0L
        val pairer = TransactionPairer(clockMs = { fakeNow })

        pairer.recordRequest(makeRequest(pairId = 4L), targetUid)
        fakeNow = TransactionPairer.TTL_MS  // 恰好 == TTL,仍算未过期

        val reply = makeReply(pairId = 4L, uid = 99999)
        val result = pairer.tryMatchReply(reply, targetUid)

        assertNotNull("TTL 边界 (== 5000ms) 仍应命中", result)
    }

    @Test
    fun `case 4 - pairId == 0 既不入表也不配对`() {
        val pairer = TransactionPairer()
        pairer.recordRequest(makeRequest(pairId = 0L), targetUid)
        assertEquals(0, pairer.size())

        val reply = makeReply(pairId = 0L, uid = 99999)
        assertNull(pairer.tryMatchReply(reply, targetUid))
    }

    @Test
    fun `case 4b - targetUid 未设 不入表也不配对`() {
        val pairer = TransactionPairer()
        pairer.recordRequest(makeRequest(pairId = 5L), 0)
        assertEquals(0, pairer.size())

        val reply = makeReply(pairId = 5L)
        assertNull(pairer.tryMatchReply(reply, 0))
    }

    @Test
    fun `case 4c - non-reply 事件传给 tryMatchReply 返回 null`() {
        val pairer = TransactionPairer()
        pairer.recordRequest(makeRequest(pairId = 6L), targetUid)
        // 错把请求当 reply 来查 → 必须 null
        assertNull(pairer.tryMatchReply(makeRequest(pairId = 6L), targetUid))
    }

    @Test
    fun `case 5 - 双方向并发 record + match 不抛 不漏配对`() {
        // 起 N 个线程做 client(uid=target)、N 个做 server(toUid=target),pairId 各占
        // 不重叠区间;每个线程 record + 立即 match 自己;断言全部命中。
        val pairer = TransactionPairer()
        val threadsPerSide = 8
        val opsPerThread = 256
        val pool = Executors.newFixedThreadPool(threadsPerSide * 2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadsPerSide * 2)
        val matched = AtomicInteger(0)
        val errors = AtomicInteger(0)

        // client 方向(uid==target)
        for (t in 0 until threadsPerSide) {
            pool.submit {
                try {
                    start.await()
                    for (i in 0 until opsPerThread) {
                        val pid = (1_000_000L + t * opsPerThread + i)
                        pairer.recordRequest(makeRequest(pairId = pid, interfaceName = "C$t", methodName = "m$i"), targetUid)
                        // reply: sender 不是 target,toUid 缺失 → 走兜底查 client 表
                        val r = pairer.tryMatchReply(makeReply(pairId = pid, uid = 99999), targetUid)
                        if (r != null && r.interfaceName == "C$t" && r.methodName == "m$i") matched.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        // server 方向(toUid==target)
        for (t in 0 until threadsPerSide) {
            pool.submit {
                try {
                    start.await()
                    for (i in 0 until opsPerThread) {
                        val pid = (2_000_000L + t * opsPerThread + i)
                        val req = makeRequest(
                            pairId = pid,
                            interfaceName = "S$t",
                            methodName = "n$i",
                            uid = 99999,
                            toUid = targetUid,
                        )
                        pairer.recordRequest(req, targetUid)
                        val r = pairer.tryMatchReply(
                            makeReply(pairId = pid, uid = targetUid, toUid = 99999),
                            targetUid,
                        )
                        if (r != null && r.interfaceName == "S$t" && r.methodName == "n$i") matched.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("并发用例应在 30s 内完成", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("不允许任何线程抛异常", 0, errors.get())
        assertEquals(
            "全部 ${threadsPerSide * 2 * opsPerThread} 次配对都应 100% 命中",
            threadsPerSide * 2 * opsPerThread,
            matched.get(),
        )
    }

    @Test
    fun `case 6 - 4096 上限 LRU evict 最早入表的被淘汰`() {
        var fakeNow = 1000L
        val pairer = TransactionPairer(clockMs = { fakeNow })

        // 先入 MAX_ENTRIES + 100 条,enqueuedAt 单调递增:fakeNow 每次 +1ms,远小于 TTL,
        // 不会触发 TTL 驱逐,纯走容量上限分支。
        val total = TransactionPairer.MAX_ENTRIES + 100
        for (i in 0 until total) {
            fakeNow += 1
            pairer.recordRequest(makeRequest(pairId = (10L + i)), targetUid)
        }

        // size 应被压回 MAX_ENTRIES(全在 client 表)
        assertEquals(TransactionPairer.MAX_ENTRIES, pairer.clientSize())
        assertEquals(0, pairer.serverSize())

        // 最早入的 100 条(pairId = 10..109)应被淘汰
        for (i in 0 until 100) {
            assertNull(
                "pairId ${10L + i} 应被 LRU evict",
                pairer.tryMatchReply(makeReply(pairId = (10L + i), uid = 99999), targetUid),
            )
        }

        // 最新入的若干条仍在表中
        assertNotNull(
            "最新入的 pairId 应保留",
            pairer.tryMatchReply(makeReply(pairId = (10L + total - 1), uid = 99999), targetUid),
        )
    }
}
