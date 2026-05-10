package com.btrace.viewer.root

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * spec § 13.2.1:验证 PID 识别不再把 `com.btrace.viewer` 之类的文本误识别为 `btrace`。
 *
 * 直接调 [BTraceManager.Companion.parsePidsStrict] —— 纯函数,无需构造 BTraceManager。
 */
class BTraceManagerPidParseTest {

    @Test
    fun pidofOutput_singlePidLine_isParsed() {
        assertEquals(listOf(24228), BTraceManager.parsePidsStrict(listOf("24228")))
    }

    @Test
    fun pidofOutput_multipleLinesWithPid_areParsed() {
        assertEquals(listOf(24228, 25519), BTraceManager.parsePidsStrict(listOf("24228", "25519")))
    }

    @Test
    fun lineWithSpaceAfterPid_firstTokenIsPid() {
        // awk 输出格式 `1234 <anything>` 的首 token 合法
        assertEquals(listOf(1234), BTraceManager.parsePidsStrict(listOf("1234 com.btrace.viewer")))
    }

    @Test
    fun packageNameOnly_isRejected() {
        // spec § 7.3 核心规避:`com.btrace.viewer` 不得被当作 PID
        assertEquals(emptyList<Int>(), BTraceManager.parsePidsStrict(listOf("com.btrace.viewer")))
    }

    @Test
    fun textFollowedByDigits_isRejected() {
        // `btrace 24228` 首 token 非数字 → 拒绝,不会把 24228 当成 PID(它可能是 ps 列表里的
        // 无关进程,不能误归为 btrace)
        assertEquals(emptyList<Int>(), BTraceManager.parsePidsStrict(listOf("btrace 24228")))
    }

    @Test
    fun blankAndEmpty_areIgnored() {
        assertEquals(emptyList<Int>(), BTraceManager.parsePidsStrict(listOf("", "   ", "\t")))
    }

    @Test
    fun pidLessThanOrEqualToOne_isRejected() {
        // init(PID 1) 绝不应被识别;0 非法
        assertEquals(emptyList<Int>(), BTraceManager.parsePidsStrict(listOf("0", "1")))
    }

    @Test
    fun duplicatesAreDeduplicatedInOrder() {
        assertEquals(listOf(100, 200), BTraceManager.parsePidsStrict(listOf("100", "100", "200")))
    }

    @Test
    fun mixedValidAndInvalid_keepsOnlyValid() {
        val pids = BTraceManager.parsePidsStrict(listOf(
            "100",
            "com.btrace.viewer",
            "200",
            "1234 com.foo"
        ))
        assertEquals(listOf(100, 200, 1234), pids)
    }

    @Test
    fun nonDecimalToken_isRejected() {
        // 十六进制 / 带后缀均不认
        assertEquals(emptyList<Int>(), BTraceManager.parsePidsStrict(listOf("0xff", "100abc", "99x")))
    }

    @Test
    fun pidWithTrailingNewline_isParsed() {
        // daemon 写 PID 带尾 \n,cat 回来的数据 trim 后仍是纯数字
        assertEquals(listOf(24228), BTraceManager.parsePidsStrict(listOf("24228\n".trimEnd())))
    }
}
