package com.btrace.viewer.parser

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * spec § 6.4 第 1 档 APK 级反查目录 — 纯 JVM 单测。
 *
 * 只覆盖 [InterfaceIndex] 内存表 + JSON 序列化/反序列化纯函数,**不触碰** SharedPreferences /
 * PackageManager(那一层留给 [InterfaceIndexBuilder] 的 instrumented 测试)。
 */
class InterfaceIndexTest {

    private lateinit var index: InterfaceIndex

    @Before
    fun setUp() {
        // Context 在内存表 + serialize/parseJson 路径里完全不会被触碰,mock 一个占位即可。
        index = InterfaceIndex(mock<Context>())
    }

    @Test
    fun `addEntry then lookup returns the package`() {
        index.addEntry("com.foo.IFoo", "com.foo.app")
        val result = index.lookup("com.foo.IFoo")
        assertEquals(setOf("com.foo.app"), result)
    }

    @Test
    fun `lookup miss returns empty set`() {
        assertTrue(index.lookup("com.unknown.IGhost").isEmpty())
        // 加完别的接口也不影响 ghost 的 miss
        index.addEntry("com.foo.IFoo", "com.foo.app")
        assertTrue(index.lookup("com.unknown.IGhost").isEmpty())
    }

    @Test
    fun `addEntry duplicate same iface and pkg is idempotent`() {
        index.addEntry("com.foo.IFoo", "com.foo.app")
        index.addEntry("com.foo.IFoo", "com.foo.app")
        index.addEntry("com.foo.IFoo", "com.foo.app")
        val result = index.lookup("com.foo.IFoo")
        assertEquals(1, result.size)
        assertEquals(setOf("com.foo.app"), result)
    }

    @Test
    fun `addEntry multiple packages for same interface accumulate`() {
        index.addEntry("com.foo.IFoo", "com.app.a")
        index.addEntry("com.foo.IFoo", "com.app.b")
        index.addEntry("com.foo.IFoo", "com.app.c")
        val result = index.lookup("com.foo.IFoo")
        assertEquals(setOf("com.app.a", "com.app.b", "com.app.c"), result)
    }

    @Test
    fun `clear empties the table`() {
        index.addEntry("com.foo.IFoo", "com.app.a")
        index.addEntry("com.bar.IBar", "com.app.b")
        assertEquals(2, index.size())
        index.clear()
        assertEquals(0, index.size())
        assertTrue(index.lookup("com.foo.IFoo").isEmpty())
    }

    @Test
    fun `serialize and parseJson roundtrip preserves contents`() {
        index.addEntry("com.foo.IFoo", "com.app.a")
        index.addEntry("com.foo.IFoo", "com.app.b")
        index.addEntry("com.bar.IBar", "com.app.c")

        // 把内存表抽出来传进 serialize(签名:Map<String, Set<String>>)
        val snapshot = mapOf(
            "com.foo.IFoo" to setOf("com.app.a", "com.app.b"),
            "com.bar.IBar" to setOf("com.app.c")
        )
        val json = index.serialize(snapshot)
        val parsed = index.parseJson(json)

        assertEquals(2, parsed.size)
        assertEquals(setOf("com.app.a", "com.app.b"), parsed["com.foo.IFoo"])
        assertEquals(setOf("com.app.c"), parsed["com.bar.IBar"])
    }

    @Test
    fun `parseJson empty object returns empty map`() {
        val parsed = index.parseJson("{}")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `parseJson malformed input returns empty map without throwing`() {
        val parsed = index.parseJson("{this is not json")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `parseJson skips entries whose value is not an array`() {
        // "com.bad" → string,跳过;"com.good" → array,保留
        val json = """{"com.bad.IBad":"notArray","com.good.IGood":["pkg.a"]}"""
        val parsed = index.parseJson(json)
        assertEquals(1, parsed.size)
        assertEquals(setOf("pkg.a"), parsed["com.good.IGood"])
    }

    // -- spec 2026-05-03 § 3.2:lookupOrdered 保留插入顺序 --

    @Test
    fun `lookupOrdered preserves first-insertion order`() {
        index.addEntry("com.foo.IFoo", "com.app.b")
        index.addEntry("com.foo.IFoo", "com.app.a")
        index.addEntry("com.foo.IFoo", "com.app.c")

        val ordered = index.lookupOrdered("com.foo.IFoo")
        assertEquals(listOf("com.app.b", "com.app.a", "com.app.c"), ordered)
    }

    @Test
    fun `lookupOrdered duplicate add does not reorder`() {
        index.addEntry("com.foo.IFoo", "com.app.x")
        index.addEntry("com.foo.IFoo", "com.app.y")
        // 重复加 x:LinkedHashSet 不会把它移到末尾
        index.addEntry("com.foo.IFoo", "com.app.x")
        index.addEntry("com.foo.IFoo", "com.app.z")

        val ordered = index.lookupOrdered("com.foo.IFoo")
        assertEquals(listOf("com.app.x", "com.app.y", "com.app.z"), ordered)
    }

    @Test
    fun `lookupOrdered miss returns empty list`() {
        assertTrue(index.lookupOrdered("com.unknown.IGhost").isEmpty())
    }

    @Test
    fun `lookup remains compatible with set semantics`() {
        // 旧 lookup 现在委托给 lookupOrdered.toSet,不影响集合语义
        index.addEntry("com.foo.IFoo", "com.app.a")
        index.addEntry("com.foo.IFoo", "com.app.b")
        val s = index.lookup("com.foo.IFoo")
        assertEquals(setOf("com.app.a", "com.app.b"), s)
    }
}
