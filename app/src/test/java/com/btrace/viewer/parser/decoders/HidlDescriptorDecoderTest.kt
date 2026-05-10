package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 3.2.1 + § 6.3.1 第 5 档:HIDL 描述符。
 */
class HidlDescriptorDecoderTest {

    private val decoder = HidlDescriptorDecoder()

    /** ASCII descriptor + NUL + 一些垃圾字节(模拟 flat_binder_object 残骸)。 */
    private fun buildHidlParcel(descriptor: String, junkLen: Int = 16): ByteArray {
        val buf = ByteArray(descriptor.length + 1 + junkLen)
        for (i in descriptor.indices) {
            buf[i] = descriptor[i].code.toByte()
        }
        buf[descriptor.length] = 0  // NUL
        // 垃圾字节填随机非 ASCII,模拟 flat_binder_object magic
        for (i in 0 until junkLen) {
            buf[descriptor.length + 1 + i] = 0x85.toByte()
        }
        return buf
    }

    private fun ev(parcel: ByteArray, dev: BinderDev = BinderDev.UNKNOWN): BinderEvent =
        BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0,
            rawParcel = parcel, binderDev = dev
        )

    @Test
    fun hidlGraphicsAllocator_isParsed() {
        val parcel = buildHidlParcel("android.hardware.graphics.allocator@4.0::IAllocator")
        val r = decoder.tryDecode(ev(parcel))
        assertNotNull(r)
        assertEquals("android.hardware.graphics.allocator@4.0::IAllocator", r!!.interfaceName)
        assertEquals(DecodeSource.HIDL_DESCRIPTOR, r.source)
        // 仅 ASCII 特征命中 → MEDIUM
        assertEquals(Confidence.MEDIUM, r.confidence)
        assertEquals(-1, r.payloadStart)
    }

    @Test
    fun hidlWithHwBinderDev_isHigh() {
        val parcel = buildHidlParcel("a.b@1.0::IFoo")
        val r = decoder.tryDecode(ev(parcel, dev = BinderDev.HWBINDER))
        assertNotNull(r)
        assertEquals(Confidence.HIGH, r!!.confidence)
    }

    @Test
    fun hwBinderWithoutShape_isMiss() {
        // 即便 binder_dev=HWBINDER,描述符没 `@`/`::` 也不应误报(让流水线继续启发式)
        val parcel = ByteArray(32) { 0x42 }  // 全 'B'
        val r = decoder.tryDecode(ev(parcel, dev = BinderDev.HWBINDER))
        assertNull(r)
    }

    @Test
    fun normalAidlParcel_isMiss() {
        // 构造一个 Q 格式 AIDL parcel,offset 0 是 0(policy),不是 ASCII,HIDL decoder 应 miss
        val buf = ByteArray(64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0).putInt(0).putInt(0x53595354).putInt(10)
        // 从 offset 16 起塞 UTF-16 'android.IFoo'
        val name = "android.IFoo"
        for (c in name) bb.putShort(c.code.toShort())
        bb.putShort(0)
        assertNull(decoder.tryDecode(ev(buf)))
    }

    @Test
    fun emptyParcel_isMiss() {
        assertNull(decoder.tryDecode(ev(ByteArray(0))))
    }

    @Test
    fun asciiNoAtSymbol_isMiss() {
        val parcel = buildHidlParcel("plain.ascii.string.no.at.symbol")
        assertNull(decoder.tryDecode(ev(parcel)))
    }
}
