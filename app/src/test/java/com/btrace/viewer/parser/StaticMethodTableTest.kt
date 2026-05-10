package com.btrace.viewer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * spec § 6.4 第 2 档静态方法表 — 纯 JVM 单测,**不依赖 Robolectric / Android Context**。
 *
 * 测试聚焦 [StaticMethodTable.parseJson] 这个纯函数:
 *   - 正常 JSON 命中(十进制 / 十六进制都要支持)
 *   - 命中错的 code 返回 null
 *   - JSON 格式错误返回空表(不 throw)
 *   - 空 JSON `{}` 返回空表
 */
class StaticMethodTableTest {

    private lateinit var table: StaticMethodTable

    @Before
    fun setUp() {
        // Context 在 parseJson 路径里完全不会被触碰,mock 一个占位即可,**不会触发 android.util.Log**。
        table = StaticMethodTable(mock())
    }

    @Test
    fun `parseJson normal JSON with decimal codes is parsed`() {
        val json = """
            {
              "com.foo.IFoo": {
                "1": "doFoo",
                "42": "doBar"
              }
            }
        """.trimIndent()
        val result = table.parseJson(json)
        assertEquals(1, result.size)
        assertEquals("doFoo", result["com.foo.IFoo"]?.get(1))
        assertEquals("doBar", result["com.foo.IFoo"]?.get(42))
    }

    @Test
    fun `parseJson hex codes are decoded as integers`() {
        val json = """
            {
              "android.hidl.base@1.0::IBase": {
                "0x00FFFFFF": "interfaceDescriptor",
                "0x00FFFFFA": "unlinkToDeath"
              }
            }
        """.trimIndent()
        val result = table.parseJson(json)
        val ibase = result["android.hidl.base@1.0::IBase"]
        assertEquals("interfaceDescriptor", ibase?.get(0x00FFFFFF))
        assertEquals("unlinkToDeath", ibase?.get(0x00FFFFFA))
    }

    @Test
    fun `parseJson mixed hex and decimal codes coexist`() {
        val json = """
            {
              "com.bar.IBar": {
                "1": "first",
                "0x10": "sixteen"
              }
            }
        """.trimIndent()
        val result = table.parseJson(json)
        assertEquals("first", result["com.bar.IBar"]?.get(1))
        assertEquals("sixteen", result["com.bar.IBar"]?.get(16))
    }

    @Test
    fun `parseJson lookup for absent code returns null`() {
        val json = """{"com.foo.IFoo":{"1":"only"}}"""
        val result = table.parseJson(json)
        assertEquals("only", result["com.foo.IFoo"]?.get(1))
        // 接口里没这个 code → null
        assertNull(result["com.foo.IFoo"]?.get(999))
        // 完全不存在的接口 → null
        assertNull(result["com.unknown.IGhost"]?.get(1))
    }

    @Test
    fun `parseJson malformed JSON returns empty map without throwing`() {
        // 不是合法 JSON
        val result = table.parseJson("{this is not json")
        assertTrue("解析失败应返回空表,实际=$result", result.isEmpty())
    }

    @Test
    fun `parseJson top-level array returns empty map`() {
        // 顶层非 object → 不解析
        val result = table.parseJson("""[{"foo":"bar"}]""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseJson empty JSON object returns empty map`() {
        val result = table.parseJson("{}")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseJson skips entries with non-numeric code keys`() {
        val json = """
            {
              "com.foo.IFoo": {
                "1": "good",
                "notANumber": "skipped",
                "2": "alsoGood"
              }
            }
        """.trimIndent()
        val result = table.parseJson(json)
        val iface = result["com.foo.IFoo"]
        assertEquals("good", iface?.get(1))
        assertEquals("alsoGood", iface?.get(2))
        // 整张子表只有 2 条合法
        assertEquals(2, iface?.size)
    }

    @Test
    fun `parseJson skips interface whose value is not an object`() {
        val json = """
            {
              "com.bad.NotObject": "stringValue",
              "com.good.IFoo": { "1": "ok" }
            }
        """.trimIndent()
        val result = table.parseJson(json)
        // 坏接口被丢弃,好接口保留
        assertNull(result["com.bad.NotObject"])
        assertEquals("ok", result["com.good.IFoo"]?.get(1))
    }

    @Test
    fun `parseJson empty interface map is dropped from result`() {
        val json = """
            {
              "com.empty.IEmpty": {},
              "com.full.IFull": { "1": "x" }
            }
        """.trimIndent()
        val result = table.parseJson(json)
        // 空 code map 不收录
        assertNull(result["com.empty.IEmpty"])
        assertEquals("x", result["com.full.IFull"]?.get(1))
    }
}
