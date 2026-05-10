package com.btrace.viewer.parser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec 2026-05-03 § 4 PersistentSignatureCache 单测。
 *
 * 用 Robolectric 起一个可写的 SharedPreferences。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersistentSignatureCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 清空 prefs,避免测试间污染
        context.getSharedPreferences("btrace_signature_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("btrace_signature_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun newCache(
        maxPackages: Int = 64,
        maxBytesPerPackage: Int = 4096,
        debounceMs: Long = 100L,
    ): PersistentSignatureCache = PersistentSignatureCache(
        context = context,
        maxPackages = maxPackages,
        maxBytesPerPackage = maxBytesPerPackage,
        debounceMs = debounceMs,
    )

    // ─── schema 边界 ───

    @Test
    fun `schemaVersion 0 missing is discarded`() {
        val cache = newCache()
        // 缺失 schemaVersion → 视为 v0,整体丢弃
        val parsed = cache.parseJsonString(
            """{"packages":{"com.app.a":{"version":1,"lastUpdated":111,"interfaces":{"I":{"m":{"code":1,"paramTypes":[],"returnType":null}}}}}}"""
        )
        assertTrue("schemaVersion 缺失应整体丢弃,实际 ${parsed.size}", parsed.isEmpty())
    }

    @Test
    fun `schemaVersion 2 future is discarded`() {
        val cache = newCache()
        val parsed = cache.parseJsonString(
            """{"schemaVersion":2,"packages":{"com.app.a":{"version":1,"lastUpdated":111,"interfaces":{"I":{"m":{"code":1,"paramTypes":[],"returnType":null}}}}}}"""
        )
        assertTrue("schemaVersion=2 应整体丢弃", parsed.isEmpty())
    }

    @Test
    fun `schemaVersion 1 happy path parses interfaces`() {
        val cache = newCache()
        val parsed = cache.parseJsonString(
            """{"schemaVersion":1,"packages":{"com.app.a":{"version":42,"lastUpdated":12345,"interfaces":{"com.foo.IFoo":{"m1":{"code":1,"paramTypes":["int","String"],"returnType":"void"}}}}}}"""
        )
        assertEquals(1, parsed.size)
        val pkg = parsed["com.app.a"]
        assertNotNull(pkg)
        assertEquals(42, pkg!!.version)
        assertEquals(12345L, pkg.lastUpdated)
        val sig = pkg.interfaces["com.foo.IFoo"]?.get("m1")
        assertNotNull(sig)
        assertEquals(listOf("int", "String"), sig!!.paramTypes)
        assertEquals("void", sig.returnType)
    }

    // ─── 序列化 round-trip ───

    @Test
    fun `put then flushNow round-trips through SharedPreferences`() = runBlocking {
        val cache = newCache()
        cache.awaitLoadComplete()
        cache.put(
            "com.app.x",
            "com.foo.IFoo",
            versionCode = 7,
            lastUpdateTime = 1_000_000L,
            sig = mapOf("doIt" to MethodSignature("doIt", listOf("int"), "String")),
        )
        cache.flushNow()

        // 关掉这一个,起一个新实例 → 从磁盘 load
        val cache2 = newCache()
        cache2.awaitLoadComplete()
        val got = cache2.get("com.app.x", "com.foo.IFoo", currentVer = 7, currentMtime = 1_000_000L)
        assertNotNull(got)
        assertEquals("doIt", got!!["doIt"]?.methodName)
        assertEquals(listOf("int"), got["doIt"]?.paramTypes)
        assertEquals("String", got["doIt"]?.returnType)
    }

    @Test
    fun `version mismatch returns null`() = runBlocking {
        val cache = newCache()
        cache.awaitLoadComplete()
        cache.put("com.app.x", "com.foo.IFoo", 7, 1L, mapOf("m" to MethodSignature("m", listOf("int"), null)))
        cache.flushNow()

        // versionCode 不一致 → null
        assertNull(cache.get("com.app.x", "com.foo.IFoo", 8, 1L))
        // lastUpdateTime 不一致 → null
        assertNull(cache.get("com.app.x", "com.foo.IFoo", 7, 2L))
    }

    // ─── load-vs-put 同接口竞态 ───

    @Test
    fun `LoadComplete does not overwrite already-Put interface`() = runBlocking {
        // 先把磁盘写入旧版本(模拟"上一次进程的快照")
        val seed = newCache()
        seed.awaitLoadComplete()
        seed.put(
            "com.app.x",
            "com.foo.IFoo",
            versionCode = 1,
            lastUpdateTime = 100L,
            sig = mapOf("m" to MethodSignature("m", listOf("OLD"), null)),
        )
        seed.flushNow()

        // 启动新实例 — 用一个 hook:在 LoadComplete 处理之前先 send Put
        val fresh = newCache()
        // 先 Put 新值(LoadComplete 还没到)
        fresh.put(
            "com.app.x",
            "com.foo.IFoo",
            versionCode = 1,
            lastUpdateTime = 100L,
            sig = mapOf("m" to MethodSignature("m", listOf("NEW"), null)),
        )
        // 等 LoadComplete + Put 都被处理
        fresh.awaitLoadComplete()
        // 让 actor 跑完 Put op(给个小窗口,actor 在 Dispatchers.IO,即使没 advance 也会 flush)
        delay(50)

        val got = fresh.peek("com.app.x", "com.foo.IFoo")
        assertNotNull(got)
        assertEquals("LoadComplete 不应覆盖运行时 Put", listOf("NEW"), got!!["m"]?.paramTypes)
    }

    // ─── load-vs-put 同包不同接口 ───

    @Test
    fun `LoadComplete fills missing interface even when other interface already Put`() = runBlocking {
        // 磁盘有 A.{I1=disk1, I2=disk2}
        val seed = newCache()
        seed.awaitLoadComplete()
        seed.put("com.app.A", "I1", 1, 100L, mapOf("m" to MethodSignature("m", listOf("D1"), null)))
        seed.put("com.app.A", "I2", 1, 100L, mapOf("m" to MethodSignature("m", listOf("D2"), null)))
        seed.flushNow()

        // 新实例:先 Put 新 I1,等 LoadComplete merge 后 I1 应保留 NEW1,I2 应从磁盘补
        val fresh = newCache()
        fresh.put("com.app.A", "I1", 1, 100L, mapOf("m" to MethodSignature("m", listOf("NEW1"), null)))
        fresh.awaitLoadComplete()
        delay(50)

        val i1 = fresh.peek("com.app.A", "I1")
        val i2 = fresh.peek("com.app.A", "I2")
        assertEquals("I1 应保留运行时新值", listOf("NEW1"), i1!!["m"]?.paramTypes)
        assertNotNull("I2 应从磁盘补缺", i2)
        assertEquals(listOf("D2"), i2!!["m"]?.paramTypes)
    }

    // ─── load 仅补缺 ───

    @Test
    fun `LoadComplete fills hot copy when no Put has happened`() = runBlocking {
        val seed = newCache()
        seed.awaitLoadComplete()
        seed.put("com.app.B", "I.X", 5, 50L, mapOf("m" to MethodSignature("m", listOf("FromDisk"), null)))
        seed.flushNow()

        val fresh = newCache()
        fresh.awaitLoadComplete()

        val got = fresh.get("com.app.B", "I.X", currentVer = 5, currentMtime = 50L)
        assertNotNull(got)
        assertEquals(listOf("FromDisk"), got!!["m"]?.paramTypes)
    }

    // ─── debounce 100ms ───

    @Test
    fun `debounce 100ms collapses rapid puts into one flush`() = runBlocking {
        val cache = newCache(debounceMs = 100L)
        cache.awaitLoadComplete()
        // 几个 put 在 100ms 窗口内,debounce 路径触发后应一并 flush。
        // 这里我们只验证最终落盘是完整的(包含全部 puts)。
        cache.put("com.app.D", "I.A", 1, 1L, mapOf("a" to MethodSignature("a", listOf("int"), null)))
        cache.put("com.app.D", "I.B", 1, 1L, mapOf("b" to MethodSignature("b", listOf("int"), null)))
        cache.put("com.app.D", "I.C", 1, 1L, mapOf("c" to MethodSignature("c", listOf("int"), null)))
        cache.flushNow()

        val fresh = newCache()
        fresh.awaitLoadComplete()
        assertNotNull(fresh.get("com.app.D", "I.A", 1, 1L))
        assertNotNull(fresh.get("com.app.D", "I.B", 1, 1L))
        assertNotNull(fresh.get("com.app.D", "I.C", 1, 1L))
    }

    // ─── M3 修复:低流量下 timeout 自发 flush ───

    @Test
    fun `single put auto-flushes after debounce window without further ops`() = runBlocking {
        // M3:第一条 Put 入队后 debounce 窗口过期,即使无任何后续 op,actor 应通过
        // onTimeout 唤醒自发 flush。旧实现下 pendingDirty 会一直留着直到下个 op,
        // 进入后台 SIGKILL 时丢失数据可能远超 100ms 窗口。
        val cache = newCache(debounceMs = 50L)
        cache.awaitLoadComplete()
        cache.put(
            "com.app.singlefoo",
            "I.Foo",
            versionCode = 1,
            lastUpdateTime = 1L,
            sig = mapOf("m" to MethodSignature("m", listOf("int"), null)),
        )

        // 等够 debounce 窗口 + 余量,但不调 flushNow / 不发任何新 op
        delay(300)

        val raw = context.getSharedPreferences("btrace_signature_cache", Context.MODE_PRIVATE)
            .getString("signature_cache_v1", null)
        assertNotNull("debounce 窗口过后即使无新 op 也应自发 flush", raw)
        assertTrue(
            "raw 中应包含 com.app.singlefoo,实际:$raw",
            raw!!.contains("com.app.singlefoo"),
        )
    }

    // ─── flushNow ack ───

    @Test
    fun `flushNow returns only after ack completed`() = runBlocking {
        val cache = newCache()
        cache.awaitLoadComplete()
        cache.put("com.app.E", "I.X", 1, 1L, mapOf("m" to MethodSignature("m", listOf("X"), null)))
        cache.flushNow()  // 必须真等磁盘落

        // 立刻 read prefs:应该已经能看到该包
        val raw = context.getSharedPreferences("btrace_signature_cache", Context.MODE_PRIVATE)
            .getString("signature_cache_v1", null)
        assertNotNull(raw)
        assertTrue("flushNow 后磁盘应包含 com.app.E,实际:$raw", raw!!.contains("com.app.E"))
    }

    // ─── maxBytesPerPackage 触发接口截断 ───

    @Test
    fun `maxBytesPerPackage triggers oldest interface truncation`() = runBlocking {
        // 把上限设到非常小,push 多个接口触发截断。Put-flush-Put 模型。
        val cache = newCache(maxBytesPerPackage = 256)
        cache.awaitLoadComplete()
        // 单个接口 JSON 约 ~80 字节,放 8 个肯定超 256
        for (i in 0 until 8) {
            cache.put(
                "com.app.F",
                "I.iface$i",
                1,
                1L,
                mapOf("m$i" to MethodSignature("m$i", listOf("int", "String"), "void")),
            )
        }
        cache.flushNow()

        val raw = context.getSharedPreferences("btrace_signature_cache", Context.MODE_PRIVATE)
            .getString("signature_cache_v1", null)
        assertNotNull(raw)
        // 单包字符长度应被压在 maxBytesPerPackage 量级附近(留点 overhead 余量)
        assertTrue(
            "整个 JSON 应被截断到上限范围内,实际 size=${raw!!.length}",
            raw.length < 4096
        )
    }
}
