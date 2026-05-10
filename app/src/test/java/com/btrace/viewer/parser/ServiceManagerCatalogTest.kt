package com.btrace.viewer.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测覆盖 ServiceManagerCatalog 的解析 / 反查关键分支。
 * 反射 Stub 走系统 ClassLoader,真机才稳;单测里只验 `service list` 行解析与
 * lookup 候选选择策略。
 */
class ServiceManagerCatalogTest {

    @Test
    fun `parseServiceList drops malformed lines and returns trimmed pairs`() {
        val catalog = ServiceManagerCatalog()
        val text = """
            Found 5 services:
            0	DockObserver: []
            1	activity: [android.app.IActivityManager]
            2	power: [android.os.IPowerManager]
            this line is garbage
            3	audio: [android.media.IAudioService]
            4	  spaced  :  [com.example.IFoo]
        """.trimIndent()
        val out = catalog.parseServiceList(text)
        // garbage 行被丢、Header 行不命中、5 条 service 全保留(包括 empty descriptor)
        assertEquals(5, out.size)
        assertEquals("DockObserver" to "", out[0])
        assertEquals("activity" to "android.app.IActivityManager", out[1])
        assertEquals("power" to "android.os.IPowerManager", out[2])
        assertEquals("audio" to "android.media.IAudioService", out[3])
        assertEquals("spaced", out[4].first)
        assertEquals("com.example.IFoo", out[4].second)
    }

    @Test
    fun `lookupByCode returns null when not warmed up`() {
        val catalog = ServiceManagerCatalog()
        assertNull(catalog.lookupByCode(1, null))
    }

    @Test
    fun `warmup empty service list yields empty snapshot`() = runBlocking {
        val catalog = ServiceManagerCatalog()
        catalog.execShell = { "" }
        catalog.warmup()
        assertNull("空 snapshot lookup 任何 code 都返回 null", catalog.lookupByCode(1, null))
        val stats = catalog.stats()
        assertNotNull(stats)
        assertEquals(0, stats!!.first)
    }

    @Test
    fun `warmup non-existent stub class skips silently`() = runBlocking {
        val catalog = ServiceManagerCatalog()
        catalog.execShell = { _ ->
            """
                Found 1 services:
                0	fake: [com.does.not.exist.IFakeStub]
            """.trimIndent()
        }
        catalog.warmup()
        // class not found → reflectStubTransactions 返回 null → 不进 byCode 表
        assertNull(catalog.lookupByCode(1, null))
        val stats = catalog.stats()
        assertNotNull(stats)
        assertEquals(1, stats!!.first)            // service list 解到 1 条
        assertEquals(0, stats.second)             // 反射成功 0 条
    }

    @Test
    fun `invalidate clears snapshot`() = runBlocking {
        val catalog = ServiceManagerCatalog()
        catalog.execShell = { "Found 0 services:" }
        catalog.warmup()
        assertNotNull(catalog.stats())
        catalog.invalidate()
        assertNull(catalog.stats())
    }

    @Test
    fun `warmup is idempotent within session`() = runBlocking {
        var shellCallCount = 0
        val catalog = ServiceManagerCatalog()
        catalog.execShell = { _ ->
            shellCallCount++
            "Found 0 services:"
        }
        catalog.warmup()
        catalog.warmup()
        catalog.warmup()
        // 第一次跑 shell,后续命中 snapshot 不再重复
        assertEquals(1, shellCallCount)
    }
}
