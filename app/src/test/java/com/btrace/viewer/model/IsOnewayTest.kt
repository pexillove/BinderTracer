package com.btrace.viewer.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec § 6.5:flags 最低位 = oneway。仅 1 类高频路径,但 UI badge 二态全靠它,
 * 加单测护住"最低位以外的位不影响判定"这条不变量。
 */
class IsOnewayTest {

    @Test
    fun lowestBitSet_isOneway() {
        assertTrue(isOneway(0x00000001))
    }

    @Test
    fun zero_isTwoway() {
        assertFalse(isOneway(0x00000000))
    }

    @Test
    fun otherBits_dontFalseTrigger() {
        // FLAG_PRIVATE_VENDOR / 0xFFFFFFFE 之类的高位组合,只要最低位 0 就是 twoway。
        assertFalse(isOneway(0x10000000))
        assertFalse(isOneway(0xFFFFFFFE.toInt()))
    }

    @Test
    fun lowestBitSetWithHighBits_stillOneway() {
        assertTrue(isOneway(0x10000001))
    }
}
