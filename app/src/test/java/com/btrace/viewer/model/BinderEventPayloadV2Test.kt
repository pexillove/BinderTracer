package com.btrace.viewer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 协议 v2 payload 解析单测(spec § 6.2)。
 *
 * 头部 60 字节布局(Big Endian):
 *   v(1) extFlags(1) reserved0(2) ts(8) pid(4) uid(4) code(4) flags(4) dataSize(4)
 *   isReply(1) binderDev(1) targetKind(1) reserved1(1) toPid(4) toUid(4)
 *   targetRef(8) pairId(8) parcel(...)
 */
class BinderEventPayloadV2Test {

    /** 构造合法 v2 payload。Parcel 区会被原样塞进 rawParcel。 */
    private fun buildPayload(
        version: Byte = 2,
        extFlags: Byte = 0,
        timestamp: Long = 1_700_000_000_000_000_000L,
        pid: Int = 1234,
        uid: Int = 10086,
        code: Int = 5,
        flags: Int = 0x10,
        isReply: Byte = 0,
        binderDev: Byte = 1,
        targetKind: Byte = 1,
        toPid: Int = 0,
        toUid: Int = 0,
        targetRef: Long = 0L,
        pairId: Long = 0L,
        parcel: ByteArray = ByteArray(0),
        // 测试越界场景:允许显式覆盖写入头部里的 dataSize 字段
        declaredDataSize: Int? = null
    ): ByteArray {
        val buf = ByteBuffer.allocate(60 + parcel.size)
        buf.put(version)
        buf.put(extFlags)
        buf.putShort(0)                  // reserved0
        buf.putLong(timestamp)
        buf.putInt(pid)
        buf.putInt(uid)
        buf.putInt(code)
        buf.putInt(flags)
        buf.putInt(declaredDataSize ?: parcel.size)
        buf.put(isReply)
        buf.put(binderDev)
        buf.put(targetKind)
        buf.put(0.toByte())              // reserved1
        buf.putInt(toPid)
        buf.putInt(toUid)
        buf.putLong(targetRef)
        buf.putLong(pairId)
        buf.put(parcel)
        return buf.array()
    }

    @Test
    fun `parses request frame`() {
        val parcel = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val payload = buildPayload(
            isReply = 0,
            pid = 4321,
            uid = 10010,
            code = 7,
            flags = 0x11,
            binderDev = 2,            // HWBINDER
            targetKind = 2,           // PTR
            toPid = 999,
            toUid = 1000,
            targetRef = 0x11_22_33_44_55_66_77_88L.toLong(),
            pairId = 0L,
            parcel = parcel
        )
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        event!!
        assertFalse(event.isReply)
        assertEquals(4321, event.pid)
        assertEquals(10010, event.uid)
        assertEquals(7, event.code)
        assertEquals(0x11, event.flags)
        assertEquals(BinderDev.HWBINDER, event.binderDev)
        assertEquals(TargetKind.PTR, event.targetKind)
        assertEquals(999, event.toPid)
        assertEquals(1000, event.toUid)
        assertEquals(0x11_22_33_44_55_66_77_88L.toLong(), event.targetRef)
        assertEquals(0L, event.pairId)
        assertEquals(parcel.toList(), event.rawParcel.toList())
    }

    @Test
    fun `parses reply frame with pair id`() {
        val payload = buildPayload(
            isReply = 1,
            binderDev = 1,
            targetKind = 0,
            pairId = 0xBEEF_CAFE_1234_5678uL.toLong(),
            parcel = byteArrayOf(0x01, 0x02)
        )
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        event!!
        assertTrue(event.isReply)
        assertEquals(BinderDev.BINDER, event.binderDev)
        assertEquals(TargetKind.UNKNOWN, event.targetKind)
        assertEquals(0xBEEF_CAFE_1234_5678uL.toLong(), event.pairId)
    }

    @Test
    fun `returns null when payload shorter than 60 bytes`() {
        // 头部不完整,无法解析。
        assertNull(BinderEvent.fromPayload(ByteArray(59)))
        assertNull(BinderEvent.fromPayload(ByteArray(0)))
    }

    @Test
    fun `returns null when version is not 2`() {
        val payload = buildPayload(version = 1)
        assertNull(BinderEvent.fromPayload(payload))
    }

    @Test
    fun `returns null when version is 0xFF`() {
        // 防御一下:负数 / 异常版本号
        val payload = buildPayload(version = 0xFF.toByte())
        assertNull(BinderEvent.fromPayload(payload))
    }

    @Test
    fun `returns null when declared dataSize exceeds remaining`() {
        // declaredDataSize=1024 但实际 parcel 只有 4 字节 → 头部之后只剩 4 字节可读
        val payload = buildPayload(
            parcel = byteArrayOf(1, 2, 3, 4),
            declaredDataSize = 1024
        )
        assertNull(BinderEvent.fromPayload(payload))
    }

    @Test
    fun `returns null when declared dataSize is negative`() {
        val payload = buildPayload(declaredDataSize = -1)
        assertNull(BinderEvent.fromPayload(payload))
    }

    @Test
    fun `target_kind out of range falls back to UNKNOWN`() {
        val payload = buildPayload(targetKind = 0x7F.toByte())
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertEquals(TargetKind.UNKNOWN, event!!.targetKind)
    }

    @Test
    fun `binder_dev out of range falls back to UNKNOWN`() {
        val payload = buildPayload(binderDev = 0x55.toByte())
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertEquals(BinderDev.UNKNOWN, event!!.binderDev)
    }

    @Test
    fun `empty parcel still produces valid event`() {
        val payload = buildPayload(parcel = ByteArray(0))
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertEquals(0, event!!.rawParcel.size)
    }
}
