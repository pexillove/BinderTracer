package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * spec § 6.3.1 第 7 档:终极兜底,**永远命中**,事件不消失。
 */
class TargetRefDecoderTest {

    private val decoder = TargetRefDecoder()

    private fun ev(targetRef: Long, code: Int = 7): BinderEvent =
        BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = code, flags = 0,
            rawParcel = ByteArray(0),
            targetRef = targetRef
        )

    @Test
    fun nonZeroTargetRef_yieldsHexFormat() {
        val r = decoder.tryDecode(ev(targetRef = 0xDEADBEEFL))
        assertNotNull(r)
        assertEquals("target@0xDEADBEEF", r.interfaceName)
        assertEquals("code=7", r.methodHint)
        assertEquals(DecodeSource.TARGET_REF, r.source)
        assertEquals(Confidence.LOW, r.confidence)
        assertEquals(-1, r.payloadStart)
    }

    @Test
    fun zeroTargetRef_yieldsUnknownLabel() {
        val r = decoder.tryDecode(ev(targetRef = 0L, code = 99))
        assertEquals("unknown", r.interfaceName)
        assertEquals("code=99", r.methodHint)
    }

    @Test
    fun emptyEvent_alwaysHits() {
        // 兜底语义:再奇葩的事件也必须返回非 null
        val r = decoder.tryDecode(ev(targetRef = 1L))
        assertNotNull(r)
    }
}
