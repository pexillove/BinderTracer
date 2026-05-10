package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 6.3.1 第 2-4 档:Java AIDL Q/P/O 三档。
 *
 * 验证不同 header 长度的 parcel 被对应档命中,且非匹配档不命中。
 */
class AidlDecoderTest {

    private val q = AidlQDecoder()
    private val p = AidlPDecoder()
    private val o = AidlODecoder()

    /** Q 格式:`[12B header (incl SYST at offset 8)][int32 length][UTF-16 chars + NUL][padding]`. */
    private fun buildQParcel(name: String): ByteArray {
        val charsBytes = (name.length + 1) * 2
        val padded = (charsBytes + 3) and 0x3.inv()
        val total = 16 + padded
        val buf = ByteArray(total)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        // policy(4) + workSource(4) = 0
        bb.putInt(0).putInt(0)
        // SYST magic at offset 8
        bb.putInt(0x53595354)
        // length at offset 12
        bb.putInt(name.length)
        // chars from offset 16
        for (c in name) bb.putShort(c.code.toShort())
        bb.putShort(0) // NUL
        return buf
    }

    /** P 格式:`[8B header][int32 length][UTF-16 chars + NUL][padding]`. */
    private fun buildPParcel(name: String): ByteArray {
        val charsBytes = (name.length + 1) * 2
        val padded = (charsBytes + 3) and 0x3.inv()
        val total = 12 + padded
        val buf = ByteArray(total)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0).putInt(0)         // 8B header
        bb.putInt(name.length)         // length at offset 8
        for (c in name) bb.putShort(c.code.toShort())
        bb.putShort(0)
        return buf
    }

    /** O 格式:`[4B header][int32 length][UTF-16 chars + NUL][padding]`. */
    private fun buildOParcel(name: String): ByteArray {
        val charsBytes = (name.length + 1) * 2
        val padded = (charsBytes + 3) and 0x3.inv()
        val total = 8 + padded
        val buf = ByteArray(total)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0)                   // 4B header
        bb.putInt(name.length)         // length at offset 4
        for (c in name) bb.putShort(c.code.toShort())
        bb.putShort(0)
        return buf
    }

    private fun ev(parcel: ByteArray): BinderEvent =
        BinderEvent(id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0, rawParcel = parcel)

    @Test
    fun qDecoder_hitsOnSystMagic() {
        val parcel = buildQParcel("android.app.IActivityManager")
        val r = q.tryDecode(ev(parcel))
        assertNotNull(r)
        assertEquals("android.app.IActivityManager", r!!.interfaceName)
        assertEquals(DecodeSource.AIDL_Q, r.source)
        assertEquals(Confidence.HIGH, r.confidence)
        // payloadStart = 16 (header+length) + padded(chars+NUL) bytes
        // chars+NUL = (28+1)*2 = 58 bytes, padded to 60
        assertEquals(16 + 60, r.payloadStart)
    }

    @Test
    fun pAndO_dontMatchQParcel() {
        // Q parcel offset 8 = SYST (0x53595354 ≈ 1398099796),作为 length 远超 4096,被 readUtf16NameAt 拦下
        val parcel = buildQParcel("android.app.IActivityManager")
        assertNull(p.tryDecode(ev(parcel)))
        // O 在 offset 4 读到的是 workSource(0),length=0 也不命中
        assertNull(o.tryDecode(ev(parcel)))
    }

    @Test
    fun pDecoder_hitsOnPParcel() {
        val parcel = buildPParcel("android.os.IServiceManager")
        val r = p.tryDecode(ev(parcel))
        assertNotNull(r)
        assertEquals("android.os.IServiceManager", r!!.interfaceName)
        assertEquals(DecodeSource.AIDL_P, r.source)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test
    fun qDecoder_doesntMatchPParcel_noMagic() {
        // P parcel 在 offset 8 是 length(假定 = 26),不是 SYST magic
        val parcel = buildPParcel("android.os.IServiceManager")
        assertNull(q.tryDecode(ev(parcel)))
    }

    @Test
    fun oDecoder_hitsOnOParcel() {
        val parcel = buildOParcel("android.os.IFoo")
        val r = o.tryDecode(ev(parcel))
        assertNotNull(r)
        assertEquals("android.os.IFoo", r!!.interfaceName)
        assertEquals(DecodeSource.AIDL_O, r.source)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test
    fun illegalLength_zero_allMiss() {
        // 构造一个 offset 12 = 0 的 Q-shape parcel
        val buf = ByteArray(32)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0).putInt(0)
        bb.putInt(0x53595354)
        bb.putInt(0)  // length = 0,非法
        assertNull(q.tryDecode(ev(buf)))
    }

    @Test
    fun illegalLength_tooLarge_allMiss() {
        val buf = ByteArray(32)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0).putInt(0)
        bb.putInt(0x53595354)
        bb.putInt(5000)  // > MAX_INTERFACE_NAME_CHARS=4096
        assertNull(q.tryDecode(ev(buf)))
    }

    @Test
    fun nameWithoutDot_isFiltered_byCharsetCheck() {
        // 没有 `.` 的字符串不算合法接口名(防 P/O 误命中乱码)
        val parcel = buildPParcel("NotADotName")
        assertNull(p.tryDecode(ev(parcel)))
    }

    @Test
    fun qDecoder_acceptsShortDescriptorWithoutDot() {
        // 真实场景:小米 Push SDK / 华为 HMS 等私有 AIDL 用短 token 当 descriptor。
        // SYST magic 已锚定,不再要求 `.`。
        val parcel = buildQParcel("Xiaomi")
        val r = q.tryDecode(ev(parcel))
        assertNotNull(r)
        assertEquals("Xiaomi", r!!.interfaceName)
        assertEquals(DecodeSource.AIDL_Q, r.source)
        assertEquals(Confidence.HIGH, r.confidence)
        // payloadStart = 16 + padded(("Xiaomi"+NUL)*2 = 14 → padded 16) = 32
        assertEquals(32, r.payloadStart)
    }

    @Test
    fun qDecoder_stillRejectsIllegalChars_evenWithMagic() {
        // 放宽只去掉 `.` 强制,但字符集合法仍是必须 —— 含空格 / 控制字符必须拒绝
        val parcel = buildQParcel("Xiao mi")
        assertNull(q.tryDecode(ev(parcel)))
    }

    @Test
    fun pDecoder_stillRejectsShortDescriptorWithoutDot() {
        // P/O 没有 magic 锚定,放宽 `.` 校验会大量误识别 → 行为不变
        val parcel = buildPParcel("Xiaomi")
        assertNull(p.tryDecode(ev(parcel)))
    }

    @Test
    fun nameWithIllegalChars_isFiltered() {
        // 含空格的字符串不应被认领
        val parcel = buildPParcel("a b.c")
        assertNull(p.tryDecode(ev(parcel)))
    }

    @Test
    fun shortParcel_returnsNull() {
        val ev = ev(ByteArray(4))  // 远不够任何档
        assertNull(q.tryDecode(ev))
        assertNull(p.tryDecode(ev))
        assertNull(o.tryDecode(ev))
    }
}
