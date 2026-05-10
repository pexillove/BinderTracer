package com.btrace.viewer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 6.3.1 第 0 档 + § 6.3.2 命中分支:Java AIDL 同步 reply 解码单测。
 *
 * 所有用例直接构造原始字节,不依赖 android.os.Parcel,保证 JVM 单测可跑。
 */
class ReplyParserTest {

    /** 构造 LE byte 序列 helper:任意 [Int] / [Long] / [String] / [Byte] 拼接。 */
    private fun bytes(vararg parts: Any): ByteArray {
        val buf = ByteBuffer.allocate(parts.sumOf { sizeOf(it) }).order(ByteOrder.LITTLE_ENDIAN)
        for (p in parts) {
            when (p) {
                is Int -> buf.putInt(p)
                is Long -> buf.putLong(p)
                is Short -> buf.putShort(p)
                is Byte -> buf.put(p)
                is ByteArray -> buf.put(p)
                is String -> {
                    // Parcel String:int32 length(字符数) + UTF-16LE chars + 2B NUL
                    buf.putInt(p.length)
                    for (c in p) buf.putShort(c.code.toShort())
                    buf.putShort(0)
                }
                else -> error("unsupported part type: ${p::class}")
            }
        }
        return buf.array()
    }

    private fun sizeOf(part: Any): Int = when (part) {
        is Int -> 4
        is Long -> 8
        is Short -> 2
        is Byte -> 1
        is ByteArray -> part.size
        is String -> 4 + part.length * 2 + 2
        else -> error("unsupported part type: ${part::class}")
    }

    @Test
    fun normal_int_return_decodes_value() {
        // [exception=0, value=42]
        val parcel = bytes(0, 42)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNull(r.exception)
        assertEquals("42", r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun normal_long_return_decodes_value() {
        val parcel = bytes(0, 1234567890123L)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "long")
        assertNull(r.exception)
        assertEquals("1234567890123", r.value)
    }

    @Test
    fun normal_boolean_return_decodes_true_and_false() {
        val rTrue = ReplyParser.decodeJavaAidlReply(bytes(0, 1), "boolean")
        assertEquals("true", rTrue.value)
        assertNull(rTrue.exception)

        val rFalse = ReplyParser.decodeJavaAidlReply(bytes(0, 0), "boolean")
        assertEquals("false", rFalse.value)
    }

    @Test
    fun normal_string_return_decodes_value() {
        val parcel = bytes(0, "hello")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "java.lang.String")
        assertNull(r.exception)
        assertEquals("hello", r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun normal_string_return_short_form_type() {
        // MethodResolver.formatTypeName 返回 short form `String`
        val parcel = bytes(0, "abc")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "String")
        assertEquals("abc", r.value)
    }

    @Test
    fun void_return_no_value_no_exception() {
        // void 方法的 reply 只有异常头,无返回值
        val parcel = bytes(0)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "void")
        assertNull(r.exception)
        assertNull(r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun short_parcel_under_exception_header_returns_raw_hex() {
        // 不到 4 字节,连异常头都读不出 → 仅给 raw hex,不抛
        val parcel = byteArrayOf(0x01, 0x02)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
        assertEquals("01 02", r.rawHexHint)
    }

    @Test
    fun empty_parcel_returns_empty_hex_hint() {
        val r = ReplyParser.decodeJavaAidlReply(ByteArray(0), "int")
        assertNull(r.exception)
        assertNull(r.value)
        assertEquals("", r.rawHexHint)
    }

    @Test
    fun exception_security_with_message() {
        // EX_SECURITY = -1, 后跟 readString(消息)
        val parcel = bytes(-1, "permission denied")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNotNull(r.exception)
        // exception 字段语义已改成 "异常摘要"(标签 + 消息),保留人类可读
        assertEquals("SecurityException: permission denied", r.exception)
        assertNull(r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun exception_illegal_argument_with_message() {
        val parcel = bytes(-3, "bad arg")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("IllegalArgumentException: bad arg", r.exception)
    }

    @Test
    fun exception_null_pointer_with_message() {
        val parcel = bytes(-4, "npe here")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("NullPointerException: npe here", r.exception)
    }

    @Test
    fun exception_illegal_state_with_message() {
        val parcel = bytes(-5, "bad state")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("IllegalStateException: bad state", r.exception)
    }

    @Test
    fun exception_unsupported_operation_with_message() {
        val parcel = bytes(-7, "no")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("UnsupportedOperationException: no", r.exception)
    }

    @Test
    fun exception_bad_parcelable_with_message() {
        val parcel = bytes(-2, "bad")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("BadParcelableException: bad", r.exception)
    }

    @Test
    fun exception_network_main_thread_with_message() {
        val parcel = bytes(-6, "no net on main")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("NetworkOnMainThreadException: no net on main", r.exception)
    }

    @Test
    fun exception_service_specific_reads_error_code_after_message() {
        // EX_SERVICE_SPECIFIC = -8, 后跟 readString(消息) 再跟 int errorCode
        val parcel = bytes(-8, "svc fail", 42)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNotNull(r.exception)
        assertEquals("ServiceSpecificException(42): svc fail", r.exception)
        assertNull(r.value)
    }

    @Test
    fun exception_parcelable_falls_back_with_label() {
        // EX_PARCELABLE = -9: 后跟 readString(消息) 然后是 parcelable 自身,
        // 我们不解 parcelable, 给标签即可
        val parcel = bytes(-9, "parcelable thrown")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNotNull(r.exception)
        assertTrue(
            "exception should mark ParcelableException: ${r.exception}",
            r.exception!!.startsWith("ParcelableException")
        )
    }

    @Test
    fun exception_message_can_be_null() {
        // 异常消息 length=-1 是合法 null,不应被当成解析失败
        val parcel = bytes(-3, -1)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNotNull(r.exception)
        // 消息缺失 → 摘要里只有标签,允许标记 (no message)
        assertEquals("IllegalArgumentException", r.exception)
    }

    @Test
    fun exception_with_truncated_message_falls_back() {
        // 异常码 -3, 消息字段不完整(len=10 但没有后续字符) → 摘要保留标签,不抛
        val parcel = bytes(-3, 10)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNotNull(r.exception)
        // 截断时仍能给出异常种类,消息标记为无法读取
        assertTrue(
            "should at least show IllegalArgumentException: ${r.exception}",
            r.exception!!.startsWith("IllegalArgumentException")
        )
    }

    @Test
    fun exception_unknown_code_uses_raw_hex() {
        // 未知正数(不是 0 也不是已知负数)→ 协议外,raw hex 兜底
        val parcel = bytes(7, 1, 2, 3)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        // 未知码不视作异常,直接 raw hex
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun strictmode_reply_header_skipped_then_no_exception_int_decoded() {
        // EX_HAS_STRICTMODE_REPLY_HEADER = -128, 后跟 int headerLen + headerLen 个 byte,
        // 然后是真正的异常码(这里 0 = 无异常),再后是返回值
        val headerBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val parcel = bytes(-128, headerBytes.size, headerBytes, 0, 99)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNull(r.exception)
        assertEquals("99", r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun appops_reply_header_skipped_then_no_exception_string_decoded() {
        // EX_HAS_NOTED_APPOPS_REPLY_HEADER = -127, 同样跳头再读真正异常码
        val headerBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parcel = bytes(-127, headerBytes.size, headerBytes, 0, "ok")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "java.lang.String")
        assertNull(r.exception)
        assertEquals("ok", r.value)
    }

    @Test
    fun strictmode_header_then_security_exception() {
        // 头跳过后跟 EX_SECURITY 异常 + 消息
        val headerBytes = byteArrayOf(0x11, 0x22)
        val parcel = bytes(-128, headerBytes.size, headerBytes, -1, "blocked")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertEquals("SecurityException: blocked", r.exception)
        assertNull(r.value)
    }

    @Test
    fun strictmode_header_with_truncated_length_falls_back() {
        // EX_HAS_STRICTMODE_REPLY_HEADER 后续没有 headerLen → raw hex 兜底
        val parcel = bytes(-128)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun strictmode_header_with_oversized_length_falls_back() {
        // headerLen 比 parcel 剩余还大 → raw hex 兜底,不抛
        val parcel = bytes(-128, 9999, 0, 0)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun unknown_return_type_falls_back_to_raw_hex() {
        // returnType = null → MethodResolver 没拿到方法签名;走 raw hex
        val parcel = bytes(0, 42, 99, 100)
        val r = ReplyParser.decodeJavaAidlReply(parcel, null)
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
        assertTrue("raw hex should not be empty", r.rawHexHint!!.isNotEmpty())
    }

    @Test
    fun unsupported_return_type_falls_back_to_raw_hex() {
        // byte[] / Bundle / 自定义 Parcelable —— 不结构化,降级 raw hex
        val parcel = bytes(0, 1, 2, 3, 4)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "byte[]")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun unsupported_return_type_bundle_also_falls_back() {
        val parcel = bytes(0, 1, 2)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "Bundle")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun int_return_truncated_after_header_falls_back() {
        // [exception=0] 但缺返回值 4 字节 → 不 throw,raw hex 兜底
        val parcel = bytes(0)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "int")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    // --- String?=null 合法返回值 vs 解析失败 ---

    @Test
    fun string_null_return_value_displayed_as_literal_null() {
        // [exception=0, length=-1] → 接口真的返回了 null,展示为字符串 "null"
        val parcel = bytes(0, -1)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "java.lang.String")
        assertNull(r.exception)
        assertEquals("null", r.value)
        // null 是合法解析结果,不应退回 raw hex
        assertNull(r.rawHexHint)
    }

    @Test
    fun string_truncated_falls_back_to_raw_hex() {
        // [exception=0, length=5] 但后面没有字符 → 真正的解析失败 → raw hex
        val parcel = bytes(0, 5)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "java.lang.String")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun charsequence_plain_null_displayed_as_literal_null() {
        // CharSequence kind=0 + length=-1 → 合法 null
        val parcel = bytes(0, 0, -1)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "CharSequence")
        assertNull(r.exception)
        assertEquals("null", r.value)
        assertNull(r.rawHexHint)
    }

    // --- CharSequence: TextUtils.writeToParcel 协议 (kind + payload) ---

    @Test
    fun charsequence_plain_kind0_decodes_string() {
        // kind=0 (plain) + readString("hello world")
        val parcel = bytes(0, 0, "hello world")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "CharSequence")
        assertNull(r.exception)
        assertEquals("hello world", r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun charsequence_plain_kind0_long_form_type() {
        val parcel = bytes(0, 0, "abc")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "java.lang.CharSequence")
        assertEquals("abc", r.value)
    }

    @Test
    fun charsequence_spannable_kind1_falls_back_to_label() {
        // kind=1 (SpannableString) → 不解 span 细节,标记降级
        // payload 后面有任意字节,本期不读
        val parcel = bytes(0, 1, "styled")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "CharSequence")
        assertNull(r.exception)
        assertEquals("<spanned charsequence>", r.value)
        assertNull(r.rawHexHint)
    }

    @Test
    fun charsequence_spanned_kind2_falls_back_to_label() {
        // kind=2 (SpannedString) → 同样标记降级
        val parcel = bytes(0, 2, "spanned")
        val r = ReplyParser.decodeJavaAidlReply(parcel, "CharSequence")
        assertEquals("<spanned charsequence>", r.value)
    }

    @Test
    fun charsequence_truncated_kind_falls_back_to_raw_hex() {
        // 异常头后没 4B kind → 读不出来,raw hex 兜底
        val parcel = bytes(0)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "CharSequence")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun charsequence_unknown_kind_falls_back_to_raw_hex() {
        // 未知 kind(超出 0/1/2)→ 协议外,raw hex 兜底
        val parcel = bytes(0, 99, 1, 2, 3)
        val r = ReplyParser.decodeJavaAidlReply(parcel, "CharSequence")
        assertNull(r.exception)
        assertNull(r.value)
        assertNotNull(r.rawHexHint)
    }

    @Test
    fun raw_hex_hint_caps_at_32_bytes_with_ellipsis() {
        // 前 4B = exceptionCode 0(无异常)+ 后续 60B 填充。byte[] 不结构化 → 走 raw hex 兜底,
        // 取前 32B 后追加 "…" 表示截断。
        val parcel = bytes(0).copyOf(64).also {
            for (i in 4 until 64) it[i] = (i and 0xFF).toByte()
        }
        val r = ReplyParser.decodeJavaAidlReply(parcel, "byte[]")
        assertNotNull(r.rawHexHint)
        assertTrue("should contain ellipsis: ${r.rawHexHint}", r.rawHexHint!!.endsWith("…"))
    }
}
