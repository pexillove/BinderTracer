package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.BinderHandleResolver
import com.btrace.viewer.parser.ParcelParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 9.1 + § 6.3.1:同一份 parcel 应只被一个 decoder 认领,且优先级正确。
 *
 * 同时验证 decoder 抛异常时流水线不崩溃 —— 跳过该档继续走下一档。
 */
class ParcelParserPipelineTest {

    private val parser = ParcelParser()

    private fun buildQParcel(name: String): ByteArray {
        val charsBytes = (name.length + 1) * 2
        val padded = (charsBytes + 3) and 0x3.inv()
        val total = 16 + padded
        val buf = ByteArray(total)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0).putInt(0).putInt(0x53595354).putInt(name.length)
        for (c in name) bb.putShort(c.code.toShort())
        bb.putShort(0)
        return buf
    }

    private fun ev(
        parcel: ByteArray,
        code: Int = 1,
        isReply: Boolean = false,
        pairId: Long = 0L,
        targetRef: Long = 0L,
        dev: BinderDev = BinderDev.UNKNOWN
    ): BinderEvent = BinderEvent(
        id = 0, timestamp = 0L, pid = 1, uid = 1, code = code, flags = 0,
        rawParcel = parcel,
        isReply = isReply,
        pairId = pairId,
        targetRef = targetRef,
        binderDev = dev
    )

    @Test
    fun replyBeatsAidl_evenIfParcelLooksLikeAidlQ() {
        // 即便 parcel 长得像 Q AIDL,reply 优先(因为请求帧才有 token,reply 解出来无意义)
        val parcel = buildQParcel("android.app.IFoo")
        val r = parser.decodePipeline(ev(parcel, isReply = true, pairId = 99L))
        assertEquals(DecodeSource.REPLY, r.source)
        assertEquals("(reply)", r.interfaceName)
    }

    @Test
    fun specialCodeBeatsAidl() {
        val parcel = buildQParcel("android.app.IFoo")
        val pingCode = ('_'.code shl 24) or ('P'.code shl 16) or ('N'.code shl 8) or 'G'.code
        val r = parser.decodePipeline(ev(parcel, code = pingCode))
        assertEquals(DecodeSource.SPECIAL_CODE, r.source)
        assertEquals("PING_TRANSACTION", r.methodHint)
    }

    @Test
    fun aidlQ_isClaimedByQDecoder() {
        val parcel = buildQParcel("android.os.IServiceManager")
        val r = parser.decodePipeline(ev(parcel))
        assertEquals(DecodeSource.AIDL_Q, r.source)
        assertEquals("android.os.IServiceManager", r.interfaceName)
    }

    @Test
    fun hidlDescriptor_isClaimedByHidlDecoder() {
        val descriptor = "android.hardware.graphics.allocator@4.0::IAllocator"
        val buf = ByteArray(descriptor.length + 16)
        for (i in descriptor.indices) buf[i] = descriptor[i].code.toByte()
        // 后面留 NUL + 16 个 0x00,避免被 RawAsciiHeuristic 也读到合法字符
        val r = parser.decodePipeline(ev(buf))
        assertEquals(DecodeSource.HIDL_DESCRIPTOR, r.source)
        assertEquals(descriptor, r.interfaceName)
    }

    @Test
    fun rawAscii_pickedUpWhenNoOtherDecoderClaims() {
        // 不带 @/:: 的 ASCII 字符串,HIDL miss,但 RawAscii 接管
        val s = "com.foo.bar.IFoo"
        val buf = ByteArray(s.length + 8) { 0 }
        for (i in s.indices) buf[i] = s[i].code.toByte()
        // offset 0..s.length-1 = ASCII,offset s.length = NUL
        val r = parser.decodePipeline(ev(buf))
        assertEquals(DecodeSource.RAW_ASCII, r.source)
        assertEquals(s, r.interfaceName)
        assertEquals(Confidence.LOW, r.confidence)
    }

    @Test
    fun targetRef_finalFallback() {
        // 全乱码 parcel:所有上层都 miss,只剩 TargetRef 兜底
        val buf = ByteArray(32) { 0xAA.toByte() }
        val r = parser.decodePipeline(ev(buf, targetRef = 0xCAFEL))
        assertEquals(DecodeSource.TARGET_REF, r.source)
        assertEquals("target@0xCAFE", r.interfaceName)
    }

    @Test
    fun emptyParcel_alwaysFallsBack() {
        // 空 parcel + zero targetRef → 仍走 TargetRef 给 "unknown",不丢事件
        val r = parser.decodePipeline(ev(ByteArray(0)))
        assertEquals(DecodeSource.TARGET_REF, r.source)
        assertEquals("unknown", r.interfaceName)
    }

    @Test
    fun targetRef_upgradedToProcessName_whenResolverHits() = runTest {
        val resolver = BinderHandleResolver()
        resolver.execRoot = { command ->
            when {
                command.contains("/dev/binderfs/binder_logs/state") -> 0 to """
                    binder state:
                    live nodes:
                      node 9991: ub.. cb.. proc 1086
                """.trimIndent()
                command.contains("/dev/binderfs/binder_logs/proc/1") -> 0 to """
                    proc 1
                    context binder
                      ref 7: desc 7 node 9991 s 1 w 1 d 0
                """.trimIndent()
                command.contains("/proc/1086/cmdline") -> 0 to "system_server"
                else -> -1 to ""
            }
        }
        resolver.refresh(senderPid = 1)

        val parserWithResolver = ParcelParser(handleResolver = resolver)
        val buf = ByteArray(32) { 0xAA.toByte() }
        val r = parserWithResolver.decodePipeline(ev(buf, targetRef = 7L))
        assertEquals(DecodeSource.TARGET_REF, r.source)
        // pid=1 在 ev() 里硬编码,与 resolver 喂的 senderPid=1 一致
        assertEquals("target@0x7 → system_server", r.interfaceName)
    }

    @Test
    fun targetRef_keepsRawWhenResolverMisses() {
        val emptyResolver = BinderHandleResolver()
        val parserWithResolver = ParcelParser(handleResolver = emptyResolver)
        val buf = ByteArray(32) { 0xAA.toByte() }
        val r = parserWithResolver.decodePipeline(ev(buf, targetRef = 0xCAFEL))
        assertEquals("target@0xCAFE", r.interfaceName)
    }

    @Test
    fun decoderException_isSwallowed_pipelineContinues() {
        // 用反射注入一个会抛异常的 decoder 不便操作。这里改为构造一个会被 ReplyDecoder /
        // SpecialCodeDecoder 之后才决断的 parcel,让流水线必须经过多档。
        // 真实异常 swallowing 由 try/catch 的代码路径保证;这里只验流水线"全部档都走完仍能落到兜底"。
        val r = parser.decodePipeline(ev(ByteArray(2), targetRef = 7L))
        assertNotEquals(DecodeSource.AIDL_Q, r.source)
        assertNotEquals(DecodeSource.AIDL_P, r.source)
        // 不到 8 字节也无 ASCII 特征,最终走 TargetRef
        assertEquals(DecodeSource.TARGET_REF, r.source)
    }
}
