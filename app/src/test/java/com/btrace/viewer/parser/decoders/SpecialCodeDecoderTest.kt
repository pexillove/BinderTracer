package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * spec § 3.2.3 + § 6.3.1 第 1 档:协议固定保留 code 表。
 *
 * 验证 6 个 ASCII 计算式 code (PING/DUMP/SHELL_COMMAND/INTERFACE/SYSPROPS/EXTENSION)
 * + 4 个 HIDL IBase code 全部命中,且 methodHint 与表一致。
 */
class SpecialCodeDecoderTest {

    private val decoder = SpecialCodeDecoder()

    private fun ev(code: Int): BinderEvent =
        BinderEvent(id = 0, timestamp = 0L, pid = 1, uid = 1, code = code, flags = 0, rawParcel = ByteArray(0))

    private fun ascii(a: Char, b: Char, c: Char, d: Char): Int =
        (a.code shl 24) or (b.code shl 16) or (c.code shl 8) or d.code

    @Test
    fun pingTransaction_isHit() {
        val r = decoder.tryDecode(ev(ascii('_', 'P', 'N', 'G')))
        assertNotNull(r)
        assertEquals("PING_TRANSACTION", r!!.methodHint)
        assertEquals("<protocol>", r.interfaceName)
        assertEquals(DecodeSource.SPECIAL_CODE, r.source)
        assertEquals(Confidence.HIGH, r.confidence)
        assertEquals(-1, r.payloadStart)
    }

    @Test
    fun dumpTransaction_isHit() {
        val r = decoder.tryDecode(ev(ascii('_', 'D', 'M', 'P')))
        assertEquals("DUMP_TRANSACTION", r?.methodHint)
    }

    @Test
    fun shellCommandTransaction_isHit() {
        val r = decoder.tryDecode(ev(ascii('_', 'C', 'M', 'D')))
        assertEquals("SHELL_COMMAND_TRANSACTION", r?.methodHint)
    }

    @Test
    fun interfaceTransaction_isHit_withCorrectAsciiCode() {
        // spec § 3.2.3 表里写 0x5F494E54 是 typo(那是 _INT),正确是 _NTF = 0x5F4E5446
        val expectedCode = ascii('_', 'N', 'T', 'F')
        assertEquals(0x5F4E5446, expectedCode)
        val r = decoder.tryDecode(ev(expectedCode))
        assertEquals("INTERFACE_TRANSACTION", r?.methodHint)
    }

    @Test
    fun syspropsTransaction_isHit() {
        val r = decoder.tryDecode(ev(ascii('_', 'S', 'P', 'R')))
        assertEquals("SYSPROPS_TRANSACTION", r?.methodHint)
    }

    @Test
    fun extensionTransaction_isHit() {
        val r = decoder.tryDecode(ev(ascii('_', 'E', 'X', 'T')))
        assertEquals("EXTENSION_TRANSACTION", r?.methodHint)
    }

    @Test
    fun hidlIbaseRange_isHit() {
        assertEquals("HIDL_PING_TRANSACTION", decoder.tryDecode(ev(0x00FFFFFC))?.methodHint)
        assertEquals("HIDL_DESCRIPTOR_CHAIN_TRANSACTION", decoder.tryDecode(ev(0x00FFFFFD))?.methodHint)
        assertEquals("HIDL_GET_DESCRIPTOR_TRANSACTION", decoder.tryDecode(ev(0x00FFFFFE))?.methodHint)
        assertEquals("HIDL_LINK_TO_DEATH_TRANSACTION", decoder.tryDecode(ev(0x00FFFFFF))?.methodHint)
    }

    @Test
    fun normalCode_isMiss() {
        assertNull(decoder.tryDecode(ev(1)))
        assertNull(decoder.tryDecode(ev(42)))
        assertNull(decoder.tryDecode(ev(0x00FFFFFB)))  // 紧贴 HIDL 区间下界
    }
}
