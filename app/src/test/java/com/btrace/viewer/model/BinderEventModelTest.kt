package com.btrace.viewer.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * spec § 13.2.4:事件详情模型测试 —— 保证解析失败时 rawParcel 仍保留原始字节,
 * 以及懒加载字段 / equals / hashCode / 时间戳格式化的健壮性。
 *
 * BinderEvent 是纯数据类,但 SimpleDateFormat 走 java.text → 不需要 Robolectric。
 */
class BinderEventModelTest {

    /** 构造合法 v2 payload(头部 60B + 自定 parcel)。 */
    private fun buildPayload(
        version: Byte = 2,
        timestamp: Long = 1_700_000_000_000_000_000L,
        pid: Int = 1234,
        uid: Int = 10086,
        code: Int = 5,
        flags: Int = 0x10,
        parcel: ByteArray = ByteArray(0),
        declaredDataSize: Int? = null
    ): ByteArray {
        val buf = ByteBuffer.allocate(60 + parcel.size)
        buf.put(version)
        buf.put(0.toByte())              // extFlags
        buf.putShort(0)                  // reserved0
        buf.putLong(timestamp)
        buf.putInt(pid)
        buf.putInt(uid)
        buf.putInt(code)
        buf.putInt(flags)
        buf.putInt(declaredDataSize ?: parcel.size)
        buf.put(0)                       // isReply
        buf.put(1)                       // binderDev = BINDER
        buf.put(1)                       // targetKind = HANDLE
        buf.put(0)                       // reserved1
        buf.putInt(0)                    // toPid
        buf.putInt(0)                    // toUid
        buf.putLong(0L)
        buf.putLong(0L)
        buf.put(parcel)
        return buf.array()
    }

    @Test
    fun `fromPayload with empty parcel keeps rawParcel size zero`() {
        val payload = buildPayload(parcel = ByteArray(0))
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertEquals(0, event!!.rawParcel.size)
    }

    @Test
    fun `lazy fields default to placeholders when never assigned`() {
        val payload = buildPayload(code = 42, uid = 10086, parcel = ByteArray(0))
        val event = BinderEvent.fromPayload(payload)!!

        // 懒加载字段未赋值时的 fallback 文案,spec § 13.2.4
        assertEquals("Unknown", event.interfaceName)
        assertEquals("code=42", event.methodName)
        assertEquals("UID:10086", event.callerPackage)
        // callee 是 substringAfterLast('.') —— "Unknown" 不含 '.' → 返回原串
        assertEquals("Unknown", event.callee)
    }

    @Test
    fun `equals and hashCode use id only, ignore timestamp and other fields`() {
        val a = BinderEvent(
            id = 7L, timestamp = 100L, pid = 1, uid = 1, code = 1, flags = 0, rawParcel = ByteArray(0)
        )
        val b = BinderEvent(
            id = 7L, timestamp = 999L, pid = 99, uid = 99, code = 99, flags = 99, rawParcel = byteArrayOf(1, 2, 3)
        )
        val c = BinderEvent(
            id = 8L, timestamp = 100L, pid = 1, uid = 1, code = 1, flags = 0, rawParcel = ByteArray(0)
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `createMock populates lazy fields and computes callee from substringAfterLast`() {
        val mock = BinderEvent.createMock(
            interfaceName = "android.app.IActivityManager",
            methodName = "startActivity",
            callerPackage = "com.foo.app"
        )
        assertEquals("android.app.IActivityManager", mock.interfaceName)
        assertEquals("startActivity", mock.methodName)
        assertEquals("com.foo.app", mock.callerPackage)
        // callee = "android.app.IActivityManager" → substringAfterLast('.') = "IActivityManager"
        assertEquals("IActivityManager", mock.callee)
    }

    @Test
    fun `flagsHex is 0x prefix with eight uppercase hex digits and zero padded`() {
        val zero = BinderEvent(0L, 0L, 0, 0, 0, 0, ByteArray(0))
        assertEquals("0x00000000", zero.flagsHex)

        val small = BinderEvent(0L, 0L, 0, 0, 0, 0x10, ByteArray(0))
        assertEquals("0x00000010", small.flagsHex)

        // 高位非负:7FFFFFFF(Int.MAX_VALUE),无 sign bit 干扰 toString(16)
        val full = BinderEvent(0L, 0L, 0, 0, 0, 0x7FFF_FFFF, ByteArray(0))
        assertEquals("0x7FFFFFFF", full.flagsHex)
    }

    @Test
    fun `formattedTime and formattedFullTime never throw for extreme timestamps`() {
        // 最大值 / 0 / 负数 都不应崩(spec § 13.2.4 健壮性)
        for (ts in longArrayOf(0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, 1_700_000_000_000_000_000L)) {
            val ev = BinderEvent(0L, ts, 0, 0, 0, 0, ByteArray(0))
            // 不抛即可,内容由 locale 决定
            assertNotNull(ev.formattedTime)
            assertNotNull(ev.formattedFullTime)
            assertTrue("formattedTime 应非空, ts=$ts", ev.formattedTime.isNotEmpty())
        }
    }

    @Test
    fun `parsedArgs and sniffedSignature default to empty list`() {
        val event = BinderEvent(0L, 0L, 0, 0, 0, 0, ByteArray(0))
        assertTrue(event.parsedArgs.isEmpty())
        assertTrue(event.sniffedSignature.isEmpty())
    }

    @Test
    fun `targetRefHex prefixes with 0x and uses uppercase hex without leading zeros`() {
        val ev = BinderEvent(
            id = 0L, timestamp = 0L, pid = 0, uid = 0, code = 0, flags = 0,
            rawParcel = ByteArray(0),
            targetRef = 0xCAFEBABEL
        )
        assertEquals("0xCAFEBABE", ev.targetRefHex)
        // 0L 没有前导 0,只输出 "0x0"
        val zero = BinderEvent(0L, 0L, 0, 0, 0, 0, ByteArray(0), targetRef = 0L)
        assertEquals("0x0", zero.targetRefHex)
    }

    /**
     * spec § 13.2.4 核心:**解析失败时仍保留原始 Parcel 字节**。
     *
     * 这里构造一个非空但格式不构成有效 Parcel 的字节串(没有 12B header / 没有 token),
     * 让 BinderEvent.fromPayload 成功解出头部、把所有字节塞进 rawParcel,但不调用任何
     * parseEvent 流程 —— 验证 rawParcel.contentEquals(原始 parcel 区字节)。
     */
    @Test
    fun `rawParcel preserves original bytes even when content is unparseable`() {
        val rawBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x00, 0x00, 0x00)
        val payload = buildPayload(parcel = rawBytes)
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertArrayEquals(rawBytes, event!!.rawParcel)
        // 即使从未 setInterfaceName / parsedArgs,rawParcel 数据也要原样保留
        assertTrue(event.parsedArgs.isEmpty())
        assertEquals("Unknown", event.interfaceName)
    }

    @Test
    fun `setting lazy fields persists subsequent reads`() {
        val event = BinderEvent(0L, 0L, 0, 0, 1, 0, ByteArray(0))
        assertEquals("code=1", event.methodName)
        event.methodName = "myMethod"
        event.interfaceName = "com.x.IFoo"
        event.callerPackage = "com.x.app"
        // 多次读应稳定,且 callee 由 interfaceName 派生
        assertEquals("myMethod", event.methodName)
        assertEquals("com.x.IFoo", event.interfaceName)
        assertEquals("com.x.app", event.callerPackage)
        assertEquals("IFoo", event.callee)
    }

    @Test
    fun `parsedArgs and sniffedSignature can be assigned and reflected back`() {
        val event = BinderEvent(0L, 0L, 0, 0, 0, 0, ByteArray(0))
        val sniff = listOf("String", "IBinder")
        event.sniffedSignature = sniff
        assertSame(sniff, event.sniffedSignature)
        assertTrue(event.parsedArgs.isEmpty())
    }

    @Test
    fun `payload with too short header returns null and never produces a partial event`() {
        // 不到 60B 的 payload 不应构造 BinderEvent —— 是 fromPayload 的 short-circuit
        assertNull(BinderEvent.fromPayload(ByteArray(10)))
        assertNull(BinderEvent.fromPayload(ByteArray(0)))
    }

    @Test
    fun `parser does not mutate original payload after fromPayload`() {
        // 防御:fromPayload 不应修改入参
        val parcel = byteArrayOf(1, 2, 3, 4)
        val payload = buildPayload(parcel = parcel)
        val snapshot = payload.copyOf()
        BinderEvent.fromPayload(payload)
        assertArrayEquals(snapshot, payload)
    }

    @Test
    fun `independent fromPayload calls produce monotonic id sequence`() {
        // idCounter 是 companion 内部状态,但每次都应该递增 —— 不要求绝对值,只要求严格 <
        val a = BinderEvent.fromPayload(buildPayload())!!
        val b = BinderEvent.fromPayload(buildPayload())!!
        val c = BinderEvent.fromPayload(buildPayload())!!
        assertTrue("id 单调递增: ${a.id} < ${b.id} < ${c.id}", a.id < b.id && b.id < c.id)
    }

    @Test
    fun `fields parsed from payload match the v2 layout`() {
        val payload = buildPayload(
            timestamp = 42L,
            pid = 7,
            uid = 8,
            code = 9,
            flags = 0xABCD
        )
        val event = BinderEvent.fromPayload(payload)!!
        assertEquals(42L, event.timestamp)
        assertEquals(7, event.pid)
        assertEquals(8, event.uid)
        assertEquals(9, event.code)
        assertEquals(0xABCD, event.flags)
        assertFalse(event.isReply)
        assertEquals(BinderDev.BINDER, event.binderDev)
    }
}
