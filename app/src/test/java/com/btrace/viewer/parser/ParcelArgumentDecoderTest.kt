package com.btrace.viewer.parser

import android.app.Notification
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Parcel
import android.os.UserHandle
import android.os.WorkSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec § 9.3 + § 9.4:验证 ParcelArgumentDecoder 在真实 Parcel API 上的行为。
 *
 * 用 Robolectric 让 Parcel.obtain / readXxx / Parcelable.Creator 走真实路径(此前
 * 此 decoder 整条主路径在 JVM 上无单测)。
 *
 * 注意:Robolectric 4.11 的 ShadowParcel 不模拟真机二进制 Parcel 布局
 * (12B header + UTF-16 token),所以测试通过 [ParcelArgumentDecoder.decodeFromParcel]
 * 直接喂一个已定位的 Parcel,跳过 computeArgStart;后者由 ParcelParserSniffTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParcelArgumentDecoderTest {

    private val decoder = ParcelArgumentDecoder()
    private val opened = mutableListOf<Parcel>()

    @After
    fun tearDown() {
        opened.forEach { it.recycle() }
        opened.clear()
    }

    /** 构造一个 Parcel,执行 fillArgs,然后倒带 dataPosition 到 0 给 decoder 读。 */
    private fun parcelOf(fillArgs: (Parcel) -> Unit): Parcel {
        val p = Parcel.obtain()
        opened += p
        fillArgs(p)
        p.setDataPosition(0)
        return p
    }

    private fun decodeOne(types: List<String>, fillArgs: (Parcel) -> Unit): DecodedArgument {
        val r = decoder.decodeFromParcel(parcelOf(fillArgs), types)
        assertTrue("应有 ${types.size} 个 arg,实得 ${r.args.size}", r.args.size == types.size)
        return r.args[0]
    }

    @Test
    fun `int long boolean float double round-trip`() {
        val p = parcelOf { p ->
            p.writeInt(42)
            p.writeLong(0x1122334455667788L)
            p.writeInt(1)         // boolean true
            p.writeFloat(3.14f)
            p.writeDouble(2.71828)
        }
        val r = decoder.decodeFromParcel(p, listOf("int", "long", "boolean", "float", "double"))
        assertTrue("整体应解析成功", r.fullySuccessful)
        assertEquals("42", r.args[0].displayValue)
        assertEquals("1234605616436508552", r.args[1].displayValue)
        assertEquals("true", r.args[2].displayValue)
        assertTrue("float 显示包含 3.14", r.args[3].displayValue.startsWith("3.14"))
        assertTrue("double 显示包含 2.718", r.args[4].displayValue.startsWith("2.718"))
    }

    @Test
    fun `byte short char round-trip`() {
        val p = parcelOf { p ->
            p.writeByte(7)
            p.writeInt(300)        // short 在 Parcel 上也按 4B 写
            p.writeInt('A'.code)   // char 同样按 int 写
        }
        val r = decoder.decodeFromParcel(p, listOf("byte", "short", "char"))
        assertTrue(r.fullySuccessful)
        assertEquals("7", r.args[0].displayValue)
        assertEquals("300", r.args[1].displayValue)
        assertEquals("A", r.args[2].displayValue)
    }

    @Test
    fun `String 中文与 ASCII 都解出引号包裹`() {
        val p = parcelOf { p ->
            p.writeString("hello")
            p.writeString("中文测试")
        }
        val r = decoder.decodeFromParcel(p, listOf("String", "String"))
        assertTrue(r.fullySuccessful)
        assertEquals("\"hello\"", r.args[0].displayValue)
        assertEquals("\"中文测试\"", r.args[1].displayValue)
    }

    @Test
    fun `Bundle 多 key 摘要展开`() {
        val arg = decodeOne(listOf("Bundle")) { p ->
            val b = android.os.Bundle()
            b.putString("k1", "v1")
            b.putInt("k2", 7)
            p.writeBundle(b)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertTrue("Bundle 摘要应该带前缀", arg.displayValue.startsWith("Bundle{"))
        assertTrue("含 k1", arg.displayValue.contains("k1="))
        assertTrue("含 k2", arg.displayValue.contains("k2="))
    }

    @Test
    fun `Intent null marker 0 显示 null`() {
        val arg = decodeOne(listOf("Intent")) { p ->
            p.writeInt(0)  // null marker
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertEquals("null", arg.displayValue)
    }

    @Test
    fun `Intent 真实值显示 toUri`() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse("https://example.com/page")
        val arg = decodeOne(listOf("Intent")) { p ->
            p.writeInt(1)  // has value
            intent.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertTrue("Intent toUri 应包含 example.com", arg.displayValue.contains("example.com"))
    }

    @Test
    fun `Uri null 与真实值都能解`() {
        val realUri = android.net.Uri.parse("content://settings/system")
        val p = parcelOf { p ->
            android.net.Uri.writeToParcel(p, null)
            android.net.Uri.writeToParcel(p, realUri)
        }
        val r = decoder.decodeFromParcel(p, listOf("Uri", "Uri"))
        assertTrue(r.fullySuccessful)
        assertEquals("null", r.args[0].displayValue)
        assertEquals("content://settings/system", r.args[1].displayValue)
    }

    @Test
    fun `Rect 通过白名单反射成功 toString`() {
        val rect = Rect(1, 2, 100, 200)
        val arg = decodeOne(listOf("Rect")) { p ->
            p.writeInt(1)            // null marker
            rect.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertTrue("应包含 Rect 坐标", arg.displayValue.contains("1") && arg.displayValue.contains("200"))
    }

    @Test
    fun `Point 通过白名单命中`() {
        val pt = Point(3, 5)
        val arg = decodeOne(listOf("Point")) { p ->
            p.writeInt(1)
            pt.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertTrue(arg.displayValue.contains("3") && arg.displayValue.contains("5"))
    }

    @Test
    fun `WorkSource 白名单命中`() {
        val ws = WorkSource()
        val arg = decodeOne(listOf("WorkSource")) { p ->
            p.writeInt(1)
            ws.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertNotNull(arg.displayValue)
    }

    @Test
    fun `UserHandle 白名单命中`() {
        val uh = UserHandle.getUserHandleForUid(0)
        val arg = decodeOne(listOf("UserHandle")) { p ->
            p.writeInt(1)
            uh.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
    }

    @Test
    fun `Notification 通过白名单反射成功`() {
        val notif = Notification.Builder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            "ch"
        ).setContentTitle("hi").build()
        val arg = decodeOne(listOf("Notification")) { p ->
            p.writeInt(1)
            notif.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertNotNull(arg.displayValue)
    }

    @Test
    fun `List of String 解成方括号 quoted`() {
        val arg = decodeOne(listOf("List<String>")) { p ->
            p.writeStringList(listOf("a", "bb", "中"))
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertEquals("[\"a\", \"bb\", \"中\"]", arg.displayValue)
    }

    @Test
    fun `List of Rect 通过白名单 createTypedArrayList`() {
        val rects = arrayListOf(Rect(0, 0, 10, 10), Rect(5, 5, 20, 20))
        val arg = decodeOne(listOf("List<Rect>")) { p ->
            p.writeTypedList(rects)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
        assertTrue("列表应有方括号", arg.displayValue.startsWith("[") && arg.displayValue.endsWith("]"))
    }

    @Test
    fun `Map of String String 直读 String 协议解出 entries`() {
        // AOSP AIDL Map<String,String> 编译输出:writeInt(N) + N x (writeString k + writeString v)
        // 例:IPackageManager.notifyDexLoad 第二个参数 classLoaderContextMap 即此模式。
        val p = parcelOf { pp ->
            pp.writeInt(2)
            pp.writeString("k1"); pp.writeString("v1")
            pp.writeString("k2"); pp.writeString("v2")
        }
        val r = decoder.decodeFromParcel(p, listOf("Map<String, String>"))
        assertEquals(1, r.args.size)
        assertEquals(DecodedArgument.Status.SUCCESS, r.args[0].status)
        val s = r.args[0].displayValue
        assertTrue("外层应为大括号 { }", s.startsWith("{") && s.endsWith("}"))
        assertTrue("含 k1:v1", s.contains("\"k1\":\"v1\""))
        assertTrue("含 k2:v2", s.contains("\"k2\":\"v2\""))
    }

    @Test
    fun `Map size -1 视作 null`() {
        val p = parcelOf { pp -> pp.writeInt(-1) }
        val r = decoder.decodeFromParcel(p, listOf("Map<String, String>"))
        assertEquals(DecodedArgument.Status.SUCCESS, r.args[0].status)
        assertEquals("null", r.args[0].displayValue)
    }

    @Test
    fun `Map 非 String 类型暂不支持 标 UNPARSED`() {
        val p = parcelOf { pp -> pp.writeInt(0) }
        val r = decoder.decodeFromParcel(p, listOf("Map<String, Object>"))
        assertEquals(DecodedArgument.Status.UNPARSED, r.args[0].status)
        assertNotNull(r.args[0].errorMessage)
    }

    @Test
    fun `Map 多于 8 项时尾部摘要省略`() {
        val p = parcelOf { pp ->
            pp.writeInt(10)
            for (i in 0 until 10) {
                pp.writeString("k$i"); pp.writeString("v$i")
            }
        }
        val r = decoder.decodeFromParcel(p, listOf("Map<String, String>"))
        assertEquals(DecodedArgument.Status.SUCCESS, r.args[0].status)
        assertTrue("应附带 …(+N) 摘要", r.args[0].displayValue.contains("…(+2)"))
    }

    @Test
    fun `notifyDexLoad 三参数全链路 String Map String`() {
        // 模拟 IPackageManager.notifyDexLoad 真实写法,验证 Map 解码不会污染下一个参数位置。
        val p = parcelOf { pp ->
            pp.writeString("com.example.app")
            pp.writeInt(1)
            pp.writeString("ctx-key"); pp.writeString("ctx-val")
            pp.writeString("arm64")
        }
        val r = decoder.decodeFromParcel(p, listOf("String", "Map<String, String>", "String"))
        assertTrue("整体应解析成功", r.fullySuccessful)
        assertEquals("\"com.example.app\"", r.args[0].displayValue)
        assertTrue(r.args[1].displayValue.contains("ctx-key"))
        assertEquals("\"arm64\"", r.args[2].displayValue)
    }

    @Test
    fun `未知类型标 UNPARSED 不抛`() {
        val p = parcelOf { p -> p.writeInt(0) }
        val r = decoder.decodeFromParcel(p, listOf("com.foo.Bar"))
        assertEquals(1, r.args.size)
        assertEquals(DecodedArgument.Status.UNPARSED, r.args[0].status)
        assertFalse(r.fullySuccessful)
        assertNotNull(r.args[0].errorMessage)
    }

    @Test
    fun `第一个参数解析失败 后续标跳过 不再读 Parcel`() {
        val p = parcelOf { p ->
            p.writeInt(0)
            p.writeString("not-read")
        }
        val r = decoder.decodeFromParcel(p, listOf("com.foo.Bar", "String"))
        assertEquals(2, r.args.size)
        assertEquals(DecodedArgument.Status.UNPARSED, r.args[0].status)
        assertEquals(DecodedArgument.Status.UNPARSED, r.args[1].status)
        assertTrue(
            "跳过提示文案",
            r.args[1].displayValue.contains("跳过") || r.args[1].errorMessage!!.contains("跳过")
        )
        assertFalse(r.fullySuccessful)
    }

    @Test
    fun `fqn 形式 android_graphics_Rect 也命中白名单`() {
        val rect = Rect(0, 0, 1, 1)
        val arg = decodeOne(listOf("android.graphics.Rect")) { p ->
            p.writeInt(1)
            rect.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
    }

    @Test
    fun `空 paramTypes decode 直接返回空结果`() {
        val r = decoder.decode(ByteArray(0), emptyList())
        assertTrue(r.args.isEmpty())
        assertTrue("没有参数也算 fullySuccessful", r.fullySuccessful)
    }

    @Test
    fun `int数组与字符串数组解成方括号格式`() {
        val p = parcelOf { p ->
            p.writeIntArray(intArrayOf(1, 2, 3))
            p.writeStringArray(arrayOf("x", "y"))
        }
        val r = decoder.decodeFromParcel(p, listOf("int[]", "String[]"))
        assertTrue(r.fullySuccessful)
        assertEquals("[1, 2, 3]", r.args[0].displayValue)
        assertEquals("[\"x\", \"y\"]", r.args[1].displayValue)
    }

    @Test
    fun `PointF 白名单命中`() {
        val pt = PointF(1.5f, 2.5f)
        val arg = decodeOne(listOf("PointF")) { p ->
            p.writeInt(1)
            pt.writeToParcel(p, 0)
        }
        assertEquals(DecodedArgument.Status.SUCCESS, arg.status)
    }
}
