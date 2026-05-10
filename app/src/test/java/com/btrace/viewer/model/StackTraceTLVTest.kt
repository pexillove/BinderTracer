package com.btrace.viewer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * spec 2026-05-03 § 4.4:STACK_TRACE TLV + 5 级 fail-open guard 链单测。
 *
 * 与 daemon `daemon/event_collector_test.go` 的 TLVGuard_* 测试逐项对应,
 * **任一级 guard 失败必须返回 stackTrace=null,绝不抛异常**(spec § 4.4.4)。
 */
class StackTraceTLVTest {

    private fun encodeFrame(buf: ByteBuffer, pc: Long, module: String, symbol: String, offset: Long) {
        buf.putLong(pc)
        val mb = module.toByteArray(Charsets.UTF_8)
        val sb = symbol.toByteArray(Charsets.UTF_8)
        buf.putShort(mb.size.toShort())
        buf.putShort(sb.size.toShort())
        buf.putLong(offset)
        buf.put(mb)
        buf.put(sb)
    }

    /** 构造合法 STACK_TRACE TLV payload(不含 5B 头) */
    private fun buildStackTLVPayload(
        quality: Byte = 0,
        truncated: Byte = 0,
        kFrames: List<Triple<Long, String, String>> = emptyList(),
        uFrames: List<Triple<Long, String, String>> = emptyList(),
        failureReason: String = ""
    ): ByteArray {
        // 估容
        var size = 8
        for ((_, m, s) in kFrames + uFrames) size += 20 + m.length + s.length
        size += 2 + failureReason.length

        val buf = ByteBuffer.allocate(size)
        buf.put(quality)
        buf.put(truncated)
        buf.putShort(0)                        // reserved
        buf.putShort(kFrames.size.toShort())
        buf.putShort(uFrames.size.toShort())
        for ((pc, m, s) in kFrames) encodeFrame(buf, pc, m, s, 0x10)
        for ((pc, m, s) in uFrames) encodeFrame(buf, pc, m, s, 0x20)
        val rb = failureReason.toByteArray(Charsets.UTF_8)
        buf.putShort(rb.size.toShort())
        buf.put(rb)
        return buf.array()
    }

    /** 拼接 base header + parcel + TLV header + TLV payload 成完整事件 frame */
    private fun buildFullPayload(
        extFlags: Byte = 0x01,
        parcel: ByteArray = ByteArray(0),
        tlvType: Byte = 0x01,
        tlvPayload: ByteArray? = null,
        declaredTlvLength: Int? = null
    ): ByteArray {
        val parcelLen = parcel.size
        val tlvSection = if (tlvPayload != null) 5 + tlvPayload.size else 0
        val buf = ByteBuffer.allocate(60 + parcelLen + tlvSection)
        buf.put(2.toByte())                    // version
        buf.put(extFlags)                      // ext_flags
        buf.putShort(0)                        // reserved0
        buf.putLong(1_700_000_000_000_000_000L)
        buf.putInt(1234)
        buf.putInt(10086)
        buf.putInt(5)
        buf.putInt(0x10)
        buf.putInt(parcelLen)
        buf.put(0)
        buf.put(1)
        buf.put(1)
        buf.put(0)
        buf.putInt(0)
        buf.putInt(0)
        buf.putLong(0)
        buf.putLong(0)
        buf.put(parcel)
        if (tlvPayload != null) {
            buf.put(tlvType)
            buf.putInt(declaredTlvLength ?: tlvPayload.size)
            buf.put(tlvPayload)
        }
        return buf.array()
    }

    /** Round-trip:Encode 后 Decode 还原 StackTrace */
    @Test
    fun roundTripStackTrace() {
        val tlv = buildStackTLVPayload(
            quality = 0,
            truncated = 0x03,
            kFrames = listOf(Triple(0xffffffc008001100uL.toLong(), "kernel", "binder_ioctl")),
            uFrames = listOf(Triple(0x7f8a000000L, "/system/lib64/libbinder.so", "IPCThreadState::transact"))
        )
        val payload = buildFullPayload(tlvPayload = tlv)
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        val st = event!!.stackTrace
        assertNotNull(st)
        assertEquals(StackQuality.FULL, st!!.quality)
        assertTrue(st.kstackTruncated)
        assertTrue(st.ustackTruncated)
        assertEquals(1, st.kFrames.size)
        assertEquals("binder_ioctl", st.kFrames[0].symbol)
        assertEquals(1, st.uFrames.size)
        assertEquals("IPCThreadState::transact", st.uFrames[0].symbol)
    }

    /** ext_flags=0 → Decode 跳过 TLV;即使后面追加垃圾字节也无影响 */
    @Test
    fun extFlagsZeroSkipsTLV() {
        val payload = buildFullPayload(extFlags = 0, tlvPayload = null)
        val withGarbage = payload + byteArrayOf(0x01, 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0xde.toByte(), 0xad.toByte())
        val event = BinderEvent.fromPayload(withGarbage)
        assertNotNull(event)
        assertNull(event!!.stackTrace)
    }

    /** Guard 1: payload < 60 → null event(base 头损坏)+ G1 计数 +1(静默) */
    @Test
    fun g1BaseHeaderTooShort() {
        val before = BinderEvent.tlvGuardG1Count.get()
        val event = BinderEvent.fromPayload(ByteArray(30))
        assertNull(event)
        // spec § 4.4.4:G1 失败 telemetry 静默累加(不写日志)
        assertEquals(before + 1, BinderEvent.tlvGuardG1Count.get())
    }

    /**
     * Guard 2: ParcelData 截断 → null event(base 自身坏掉)+ G2 计数 +1(静默)。
     * 构造 declared dataSize=100 但实际只有 60 字节(< 60+100)。
     */
    @Test
    fun g2ParcelTruncated() {
        val before = BinderEvent.tlvGuardG2Count.get()
        // 直接构造一个 base 头但 dataSize=100 的 70B payload(payload < 60+100)
        val buf = ByteBuffer.allocate(70)
        buf.put(2.toByte())
        buf.put(0x01.toByte())
        buf.putShort(0)
        buf.putLong(0)
        buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        buf.putInt(100)                        // 故意写大
        buf.put(0); buf.put(1); buf.put(1); buf.put(0)
        buf.putInt(0); buf.putInt(0)
        buf.putLong(0); buf.putLong(0)
        // 后面只有 70-60=10 字节,不够 100
        repeat(10) { buf.put(0) }
        val event = BinderEvent.fromPayload(buf.array())
        assertNull(event)
        assertEquals(before + 1, BinderEvent.tlvGuardG2Count.get())
    }

    /** Guard 4: ext_flags 置位但 TLV 头不完整(只 3B)→ event 仍正常,stackTrace=null */
    @Test
    fun g4TLVHeaderTruncated() {
        val buf = ByteBuffer.allocate(60 + 3)
        buf.put(2.toByte())
        buf.put(0x01.toByte())
        buf.putShort(0)
        buf.putLong(0)
        buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        buf.putInt(0)
        buf.put(0); buf.put(1); buf.put(1); buf.put(0)
        buf.putInt(0); buf.putInt(0)
        buf.putLong(0); buf.putLong(0)
        buf.put(0x01); buf.putShort(0)         // 只 3B,缺 2B
        val event = BinderEvent.fromPayload(buf.array())
        assertNotNull(event)
        assertNull(event!!.stackTrace)
    }

    /** Guard 5: tlv_length 超过剩余字节 → fail-open(stackTrace=null) */
    @Test
    fun g5TLVPayloadOverflow() {
        val tlv = buildStackTLVPayload()
        val payload = buildFullPayload(tlvPayload = tlv, declaredTlvLength = 9999)
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertNull(event!!.stackTrace)
    }

    /** 未知 TLV 类型 → 跳过(向前兼容,不视为错误) */
    @Test
    fun unknownTLVTypeIsSilent() {
        val tlv = buildStackTLVPayload()
        val payload = buildFullPayload(tlvType = 0x99.toByte(), tlvPayload = tlv)
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertNull(event!!.stackTrace)
    }

    /** stripped .so:DEGRADED quality */
    @Test
    fun strippedDynsymDegraded() {
        val tlv = buildStackTLVPayload(
            quality = 2, // DEGRADED
            uFrames = listOf(Triple(0x7f8a000000L, "/data/app/lib/foo.so", "Java_foo"))
        )
        val payload = buildFullPayload(tlvPayload = tlv)
        val event = BinderEvent.fromPayload(payload)
        assertNotNull(event)
        assertEquals(StackQuality.DEGRADED, event!!.stackTrace!!.quality)
    }

    /** truncated 标志:bit0=kstack 满,bit1=ustack 满 */
    @Test
    fun truncatedFlagBits() {
        val tlv = buildStackTLVPayload(truncated = 0x01) // 只 kstack 满
        val payload = buildFullPayload(tlvPayload = tlv)
        val st = BinderEvent.fromPayload(payload)!!.stackTrace!!
        assertTrue(st.kstackTruncated)
        assertFalse(st.ustackTruncated)
    }

    /**
     * Fuzz:多种 truncated / 越界 / extFlags 边界,断言永不抛异常,
     * 且失败路径一律 stackTrace=null。
     *
     * 与 daemon `TestTLVGuard_FuzzNeverPanics` 用相同尺寸列表,基础 smoke 测试。
     * 真正的双端 cross-check 见 [sharedFuzzVectorsCrossCheck]。
     */
    @Test
    fun fuzzNeverThrows() {
        val sizes = listOf(0, 5, 30, 59, 60, 65, 70, 100, 200, 1000)
        for (sz in sizes) {
            val buf = ByteArray(sz)
            if (sz > 0) buf[0] = 2
            if (sz >= 2 && sz % 2 == 0) buf[1] = 0x01
            // 不应抛异常
            val event = try {
                BinderEvent.fromPayload(buf)
            } catch (e: Throwable) {
                throw AssertionError("payload size $sz threw: ${e.message}", e)
            }
            // event 可能为 null(G1/G2 失败),也可能非 null(stackTrace 应为 null)
            if (event != null) {
                assertNull("size=$sz extFlags=${buf.getOrNull(1)}: stackTrace 必须 null", event.stackTrace)
            }
        }
    }

    /**
     * spec § 4.4.4 + § 13 D5:双端共享 fuzz vectors cross-check。
     *
     * 同一份 testdata/tlv-fuzz-vectors.bin 由 daemon Go 与 app Kotlin 共同消费,
     * 每条 vector 带 expectStackNull 标记,两端按 spec § 4.4.4 5 级 guard 解码后
     * 必须**对每条 vector 给出相同的 stackTrace null/非 null 判定**。
     *
     * 文件路径:
     *   daemon/testdata/tlv-fuzz-vectors.bin            (Go 端测试读这里)
     *   app/src/test/resources/tlv-fuzz-vectors.bin     (Kotlin 端测试读这里;两份字节序列严格相同)
     *
     * 修改任一端 5 级 guard 时,**必须**:
     *   1. 重跑 daemon/testdata/tlv_fuzz_gen.go 生成 .bin
     *   2. cp 到 app/src/test/resources/
     *   3. 跑两端测试确保 expectStackNull 都通过
     *
     * daemon 端对应测试:TestTLVGuard_SharedFuzzVectorsCrossCheck
     */
    @Test
    fun sharedFuzzVectorsCrossCheck() {
        val data = javaClass.classLoader!!.getResourceAsStream("tlv-fuzz-vectors.bin")!!.use {
            it.readBytes()
        }
        check(data.size >= 6) { "vectors file too short" }
        val magic = String(data, 0, 4, Charsets.US_ASCII)
        check(magic == "TLVF") { "bad magic: $magic" }
        val cnt = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)

        var off = 6
        for (i in 0 until cnt) {
            check(off + 5 <= data.size) { "vector $i header truncated" }
            val size = ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
            val expectStackNull = data[off + 2] != 0.toByte()
            val noteLen = ((data[off + 3].toInt() and 0xff) shl 8) or (data[off + 4].toInt() and 0xff)
            off += 5
            check(off + noteLen + size <= data.size) { "vector $i body truncated" }
            val note = String(data, off, noteLen, Charsets.UTF_8)
            off += noteLen
            val payload = data.copyOfRange(off, off + size)
            off += size

            val event = try {
                BinderEvent.fromPayload(payload)
            } catch (t: Throwable) {
                throw AssertionError("vector #$i \"$note\" threw: ${t.message}", t)
            }
            val gotNull = event == null || event.stackTrace == null
            assertEquals(
                "vector #$i \"$note\": expectStackNull=$expectStackNull but got null=$gotNull",
                expectStackNull,
                gotNull
            )
        }
    }
}
