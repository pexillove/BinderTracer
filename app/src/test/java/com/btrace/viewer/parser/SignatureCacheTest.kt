package com.btrace.viewer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureCacheTest {
    @Test
    fun `cache stores and retrieves by package + interface key`() {
        val cache = SignatureCache()
        val sig = MethodSignature("doIt", listOf("java.lang.String"), "void")

        cache.put("com.app.a", "android.app.IFoo", sig)

        assertEquals(sig, cache.get("com.app.a", "android.app.IFoo", "doIt"))
        assertNull(
            "different package must miss",
            cache.get("com.app.b", "android.app.IFoo", "doIt")
        )
    }

    @Test
    fun `cache without package treats null and empty as the same bucket`() {
        val cache = SignatureCache()
        val sig = MethodSignature("doIt", listOf("int"), "void")
        cache.put(null, "android.app.IFoo", sig)

        assertEquals(sig, cache.get("", "android.app.IFoo", "doIt"))
        assertEquals(sig, cache.get(null, "android.app.IFoo", "doIt"))
    }

    @Test
    fun `empty result is never cached and triggers re-load`() {
        val cache = SignatureCache()
        var loadCount = 0
        val loader: (String?, String) -> Map<String, MethodSignature> = { _, _ ->
            loadCount++
            emptyMap()
        }

        cache.getOrLoad("com.app.a", "android.app.IFoo", loader)
        cache.getOrLoad("com.app.a", "android.app.IFoo", loader)

        assertEquals("empty result must NOT be cached", 2, loadCount)
    }

    @Test
    fun `non-empty result is cached and load runs only once`() {
        val cache = SignatureCache()
        var loadCount = 0
        val loader: (String?, String) -> Map<String, MethodSignature> = { _, _ ->
            loadCount++
            mapOf("doIt" to MethodSignature("doIt", listOf("int"), "void"))
        }

        cache.getOrLoad("com.app.a", "android.app.IFoo", loader)
        cache.getOrLoad("com.app.a", "android.app.IFoo", loader)

        assertEquals(1, loadCount)
    }

    @Test
    fun `invalidate by package clears all interfaces under that package`() {
        val cache = SignatureCache()
        cache.put("com.app.a", "android.app.IFoo", MethodSignature("doIt", listOf("int"), "void"))
        cache.put("com.app.a", "android.app.IBar", MethodSignature("go", listOf("long"), "boolean"))
        cache.put("com.app.b", "android.app.IFoo", MethodSignature("doIt", listOf("int"), "void"))

        cache.invalidatePackage("com.app.a")

        assertNull(cache.get("com.app.a", "android.app.IFoo", "doIt"))
        assertNull(cache.get("com.app.a", "android.app.IBar", "go"))
        assertEquals(
            "other packages should be untouched",
            MethodSignature("doIt", listOf("int"), "void"),
            cache.get("com.app.b", "android.app.IFoo", "doIt")
        )
    }

    @Test
    fun `put overwrites previous value for same method name`() {
        val cache = SignatureCache()
        cache.put("com.app.a", "android.app.IFoo", MethodSignature("doIt", listOf("int"), "void"))
        cache.put(
            "com.app.a",
            "android.app.IFoo",
            MethodSignature("doIt", listOf("long", "java.lang.String"), "java.lang.String")
        )

        assertEquals(
            MethodSignature("doIt", listOf("long", "java.lang.String"), "java.lang.String"),
            cache.get("com.app.a", "android.app.IFoo", "doIt")
        )
    }

    @Test
    fun `returnType is preserved through put and get`() {
        val cache = SignatureCache()
        val sig = MethodSignature("queryUser", listOf("int"), "com.example.User")
        cache.put("com.app.a", "com.example.IUserSvc", sig)

        val got = cache.get("com.app.a", "com.example.IUserSvc", "queryUser")
        assertNotNull(got)
        assertEquals("com.example.User", got!!.returnType)
        assertEquals(listOf("int"), got.paramTypes)
    }

    // spec 2026-05-03 § 4.4:LRU 256 上限可调
    @Test
    fun `LRU evicts oldest entry when over capacity`() {
        val cache = SignatureCache(maxEntries = 3)
        cache.put("p1", "I.A", MethodSignature("a", listOf("int"), "void"))
        cache.put("p1", "I.B", MethodSignature("b", listOf("int"), "void"))
        cache.put("p1", "I.C", MethodSignature("c", listOf("int"), "void"))
        // 触发 evict:I.A 是最老
        cache.put("p1", "I.D", MethodSignature("d", listOf("int"), "void"))

        assertEquals(3, cache.size())
        assertNull(cache.get("p1", "I.A", "a"))
        assertEquals("d", cache.get("p1", "I.D", "d")?.methodName)
    }

    @Test
    fun `LRU access promotes entry to most-recent`() {
        val cache = SignatureCache(maxEntries = 3)
        cache.put("p1", "I.A", MethodSignature("a", listOf("int"), "void"))
        cache.put("p1", "I.B", MethodSignature("b", listOf("int"), "void"))
        cache.put("p1", "I.C", MethodSignature("c", listOf("int"), "void"))
        // 访问 A → A 应移到 tail,B 变成最老
        cache.get("p1", "I.A", "a")
        cache.put("p1", "I.D", MethodSignature("d", listOf("int"), "void"))

        assertEquals(3, cache.size())
        assertNotNull("A 因被 access 不应被 evict", cache.get("p1", "I.A", "a"))
        assertNull("B 是最老,应被 evict", cache.get("p1", "I.B", "b"))
    }

    @Test
    fun `null returnType is stored and retrieved without being treated as empty`() {
        val cache = SignatureCache()
        var loadCount = 0
        // returnType=null 但 paramTypes 已填 —— 模拟"接口反射出 paramTypes 但 Method 的 returnType
        // 类不在 ClassLoader 上"的边角:不能被当成空结果丢弃,必须缓存。
        val loader: (String?, String) -> Map<String, MethodSignature> = { _, _ ->
            loadCount++
            mapOf("opaque" to MethodSignature("opaque", listOf("android.os.Bundle"), null))
        }

        val first = cache.getOrLoad("com.app.a", "com.foo.IBlackbox", loader)
        val second = cache.getOrLoad("com.app.a", "com.foo.IBlackbox", loader)

        assertEquals("loader 应只跑一次,returnType=null 不算空结果", 1, loadCount)
        assertEquals(first, second)
        val got = cache.get("com.app.a", "com.foo.IBlackbox", "opaque")
        assertNotNull(got)
        assertNull(got!!.returnType)
        assertEquals(listOf("android.os.Bundle"), got.paramTypes)
    }
}
