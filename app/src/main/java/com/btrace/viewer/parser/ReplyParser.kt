package com.btrace.viewer.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 6.3.1 第 0 档 + § 6.3.2 命中分支:Java AIDL 同步 reply 的"异常头 + 返回值"结构化解码。
 *
 * Java AIDL 同步 reply parcel 起始 4 字节是 Android `Parcel.writeException` / `writeNoException`
 * 写出的异常码,真实协议:
 *   - `0` = 无异常,后跟返回值
 *   - 已知负数 = 标准异常码,后跟一个 [readUtf16String] 形式的消息字符串
 *     (`EX_SECURITY=-1`, `EX_BAD_PARCELABLE=-2`, `EX_ILLEGAL_ARGUMENT=-3`, `EX_NULL_POINTER=-4`,
 *     `EX_ILLEGAL_STATE=-5`, `EX_NETWORK_MAIN_THREAD=-6`, `EX_UNSUPPORTED_OPERATION=-7`,
 *     `EX_SERVICE_SPECIFIC=-8` 还要在消息后多读 1 个 int errorCode,
 *     `EX_PARCELABLE=-9` 后跟消息再跟 parcelable 自身,本类只读消息标签)
 *   - `EX_HAS_STRICTMODE_REPLY_HEADER=-128` / `EX_HAS_NOTED_APPOPS_REPLY_HEADER=-127` =
 *     不是异常,是 StrictMode/AppOps 头:开头 4B 是 header 长度,后跟 header bytes,跳过头
 *     后递归继续读真正的异常码 / 返回值。
 *   - 其它未知值 → 不当成异常,raw hex 兜底,避免误判。
 *
 * **范围**:仅同步 reply 有这个头;oneway 无 reply parcel,HIDL reply 直接是返回值,本类不接管。
 * 调用方([decodeJavaAidlReply])对 HIDL / 未知 returnType 走 raw hex 摘要降级,不抛错。
 *
 * **纯逻辑**:只接 ByteArray + returnType 字符串,无 Context / Hilt / Parcel API,便于 JVM 单测。
 */
object ReplyParser {

    /**
     * @param exception   非 null = reply 携带 Java 异常,值是"异常摘要"(异常种类标签 + 消息),
     *                    例如 `SecurityException: permission denied`;消息缺失时只保留标签。
     * @param value       已结构化的返回值(int/long/boolean/String/void → null);其它类型不结构化时为 null
     * @param rawHexHint  无法或不愿结构化时的前 32 字节 hex 摘要,供 UI 显示一行兜底信息
     */
    data class ReplyDecodeResult(
        val exception: String?,
        val value: String?,
        val rawHexHint: String?
    )

    /** raw hex 摘要的最大字节数,避免详情页被一长串 hex 撑爆。 */
    private const val RAW_HEX_HINT_BYTES = 32

    // android.os.Parcel 的异常码常量,详见 frameworks/base Parcel.java
    private const val EX_SECURITY = -1
    private const val EX_BAD_PARCELABLE = -2
    private const val EX_ILLEGAL_ARGUMENT = -3
    private const val EX_NULL_POINTER = -4
    private const val EX_ILLEGAL_STATE = -5
    private const val EX_NETWORK_MAIN_THREAD = -6
    private const val EX_UNSUPPORTED_OPERATION = -7
    private const val EX_SERVICE_SPECIFIC = -8
    private const val EX_PARCELABLE = -9
    private const val EX_HAS_NOTED_APPOPS_REPLY_HEADER = -127
    private const val EX_HAS_STRICTMODE_REPLY_HEADER = -128

    /** StrictMode/AppOps 头的最大允许长度,防止畸形大值触发巨量分配。 */
    private const val MAX_REPLY_HEADER_BYTES = 64 * 1024

    /**
     * 解码 Java AIDL 同步 reply。
     *
     * @param parcel     reply 帧的完整 parcel 字节(无 binder header)
     * @param returnType MethodResolver 拿到的返回类型短名(`int` / `boolean` / `void` /
     *                    `java.lang.String` / `Bundle` / `byte[]` 等)。null = 未拿到方法签名,
     *                    走 raw hex 兜底。
     */
    fun decodeJavaAidlReply(parcel: ByteArray, returnType: String?): ReplyDecodeResult {
        // 异常头本身就 4 字节,parcel 短于此 → 完全无法判断,只给 raw hex 兜底
        if (parcel.size < 4) {
            return ReplyDecodeResult(
                exception = null,
                value = null,
                rawHexHint = rawHexHint(parcel)
            )
        }

        val buffer = ByteBuffer.wrap(parcel).order(ByteOrder.LITTLE_ENDIAN)

        // 跳过 0..N 个 reply header(StrictMode / AppOps),拿到真正的异常码
        val exceptionCode = readExceptionCode(buffer)
            ?: return ReplyDecodeResult(
                // header 协议不完整 / 截断 → 整体降级 raw hex,不抛
                exception = null,
                value = null,
                rawHexHint = rawHexHint(parcel)
            )

        if (exceptionCode != 0) {
            // 已知异常码 → 读消息(可能是 null 或截断);未知码 → raw hex 兜底
            val summary = decodeException(buffer, exceptionCode)
                ?: return ReplyDecodeResult(
                    exception = null,
                    value = null,
                    rawHexHint = rawHexHint(parcel)
                )
            return ReplyDecodeResult(
                exception = summary,
                value = null,
                rawHexHint = null
            )
        }

        // 无异常分支:按 returnType 解一层。剩余字节是返回值的 parcel 编码。
        if (returnType == null) {
            return ReplyDecodeResult(
                exception = null,
                value = null,
                rawHexHint = rawHexHint(parcel)
            )
        }

        return when (canonicalReturnType(returnType)) {
            "void" -> ReplyDecodeResult(exception = null, value = null, rawHexHint = null)
            "int" -> readIntValue(buffer, parcel)
            "long" -> readLongValue(buffer, parcel)
            "boolean" -> readBooleanValue(buffer, parcel)
            "String" -> readStringValue(buffer, parcel)
            "CharSequence" -> readCharSequenceValue(buffer, parcel)
            else -> ReplyDecodeResult(
                exception = null,
                value = null,
                rawHexHint = rawHexHint(parcel)
            )
        }
    }

    /**
     * 读取并跳过任意数量的 reply header,返回真正的异常码;header 截断或长度异常时返回 null。
     *
     * 对应 `Parcel.readExceptionCode()`:遇到 `EX_HAS_STRICTMODE_REPLY_HEADER` 或
     * `EX_HAS_NOTED_APPOPS_REPLY_HEADER` 时,后续是 `int headerLen + headerLen 个 byte`,
     * 跳过后递归再读 4B 异常码。
     */
    private fun readExceptionCode(buffer: ByteBuffer): Int? {
        // 防御性上限,虽然实际只可能嵌 1~2 层
        repeat(4) {
            if (buffer.remaining() < 4) return null
            val code = buffer.int
            if (code != EX_HAS_STRICTMODE_REPLY_HEADER && code != EX_HAS_NOTED_APPOPS_REPLY_HEADER) {
                return code
            }
            // 跳过 header:int headerLen + headerLen 个 byte
            if (buffer.remaining() < 4) return null
            val headerLen = buffer.int
            if (headerLen < 0 || headerLen > MAX_REPLY_HEADER_BYTES) return null
            if (buffer.remaining() < headerLen) return null
            buffer.position(buffer.position() + headerLen)
            // 继续 loop 再读下一个 4B 异常码
        }
        return null
    }

    /**
     * 把已知异常码 + 后续消息字段拼成"异常摘要"(`Tag` 或 `Tag: msg` 或 `ServiceSpecificException(N): msg`)。
     *
     * - 已知异常码:tag 取自常量映射;消息走 [readUtf16String],null 表示合法 null,失败保留 tag
     * - `EX_SERVICE_SPECIFIC` 在消息后再读一个 int errorCode
     * - 未知异常码:返回 null,调用方降级 raw hex
     */
    private fun decodeException(buffer: ByteBuffer, code: Int): String? {
        val tag = exceptionTag(code) ?: return null

        // 标签 + 可选消息;消息字段缺失/截断时只保留 tag,而不是整体退回 raw hex,
        // 因为我们已经知道这是个异常,丢失消息比丢失整个事实更可接受
        val message = if (buffer.remaining() < 4) null else readMessageField(buffer)

        if (code == EX_SERVICE_SPECIFIC) {
            // 消息后再读 1 个 int errorCode;读不到就只给标签
            val errorCode = if (buffer.remaining() >= 4) buffer.int else null
            return when {
                errorCode != null && message != null -> "ServiceSpecificException($errorCode): $message"
                errorCode != null -> "ServiceSpecificException($errorCode)"
                message != null -> "ServiceSpecificException: $message"
                else -> "ServiceSpecificException"
            }
        }

        return if (message.isNullOrEmpty()) tag else "$tag: $message"
    }

    /**
     * 读异常消息字段:
     *   - 正常字符串 → 返回原文(可能空串)
     *   - length=-1(合法 null) → 返回 null,摘要只保留 tag(异常本身没带消息很常见)
     *   - 字段截断 / 长度异常 → 返回 null,摘要只保留 tag(避免整体退回 raw hex,
     *     至少把"异常种类"信息留给用户)
     */
    private fun readMessageField(buffer: ByteBuffer): String? =
        when (val r = readUtf16String(buffer)) {
            is StringReadResult.Success -> r.value
            StringReadResult.NullValue -> null
            StringReadResult.Failure -> null
        }

    private fun exceptionTag(code: Int): String? = when (code) {
        EX_SECURITY -> "SecurityException"
        EX_BAD_PARCELABLE -> "BadParcelableException"
        EX_ILLEGAL_ARGUMENT -> "IllegalArgumentException"
        EX_NULL_POINTER -> "NullPointerException"
        EX_ILLEGAL_STATE -> "IllegalStateException"
        EX_NETWORK_MAIN_THREAD -> "NetworkOnMainThreadException"
        EX_UNSUPPORTED_OPERATION -> "UnsupportedOperationException"
        EX_SERVICE_SPECIFIC -> "ServiceSpecificException"
        EX_PARCELABLE -> "ParcelableException"
        else -> null
    }

    /**
     * 把反射得到的 returnType 名归一化成本类支持的内部 token。MethodResolver.formatTypeName
     * 已经把 java.lang.* 截短成 `String` / `Integer`,所以这里同时认 short / long form。
     *
     * 注意:`CharSequence` 不并入 `String`,因为 Android 的 CharSequence 走的是
     * `TextUtils.writeToParcel` 协议(`int kind + payload`),不是普通 String 布局,
     * 详见 [readCharSequenceValue]。
     */
    private fun canonicalReturnType(returnType: String): String = when (returnType) {
        "int", "Integer", "java.lang.Integer" -> "int"
        "long", "Long", "java.lang.Long" -> "long"
        "boolean", "Boolean", "java.lang.Boolean" -> "boolean"
        "void", "Void", "java.lang.Void" -> "void"
        "String", "java.lang.String" -> "String"
        "CharSequence", "java.lang.CharSequence" -> "CharSequence"
        else -> returnType
    }

    private fun readIntValue(buffer: ByteBuffer, parcel: ByteArray): ReplyDecodeResult {
        if (buffer.remaining() < 4) {
            return ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
        }
        return ReplyDecodeResult(exception = null, value = buffer.int.toString(), rawHexHint = null)
    }

    private fun readLongValue(buffer: ByteBuffer, parcel: ByteArray): ReplyDecodeResult {
        if (buffer.remaining() < 8) {
            return ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
        }
        return ReplyDecodeResult(exception = null, value = buffer.long.toString(), rawHexHint = null)
    }

    private fun readBooleanValue(buffer: ByteBuffer, parcel: ByteArray): ReplyDecodeResult {
        if (buffer.remaining() < 4) {
            return ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
        }
        return ReplyDecodeResult(exception = null, value = (buffer.int != 0).toString(), rawHexHint = null)
    }

    private fun readStringValue(buffer: ByteBuffer, parcel: ByteArray): ReplyDecodeResult =
        when (val r = readUtf16String(buffer)) {
            is StringReadResult.Success ->
                ReplyDecodeResult(exception = null, value = r.value, rawHexHint = null)
            // 接口真的返回了 null —— 让用户看见 "null" 而不是 raw hex
            StringReadResult.NullValue ->
                ReplyDecodeResult(exception = null, value = "null", rawHexHint = null)
            StringReadResult.Failure ->
                ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
        }

    /**
     * 读 `CharSequence` 返回值,走 `android.text.TextUtils.writeToParcel` 协议:
     *   `int kind + ... 按 kind 分支`
     *   - kind=0 plain → 后接 readString(普通 UTF-16 字符串布局)
     *   - kind=1 SpannableString / kind=2 SpannedString → 不解 span 细节,
     *     给 `<spanned charsequence>` 标签兜底
     *   - 其它 kind / 截断 → raw hex 兜底
     *
     * 与 [ParcelArgumentDecoder] 用 `TextUtils.CHAR_SEQUENCE_CREATOR` 的官方读取入口对齐
     * (那边能调 Android Parcel API,本类是纯 JVM,只能照协议手撸)。
     */
    private fun readCharSequenceValue(buffer: ByteBuffer, parcel: ByteArray): ReplyDecodeResult {
        if (buffer.remaining() < 4) {
            return ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
        }
        return when (val kind = buffer.int) {
            0 -> when (val s = readUtf16String(buffer)) {
                // plain charsequence == 普通 String 布局
                is StringReadResult.Success ->
                    ReplyDecodeResult(exception = null, value = s.value, rawHexHint = null)
                StringReadResult.NullValue ->
                    ReplyDecodeResult(exception = null, value = "null", rawHexHint = null)
                StringReadResult.Failure ->
                    ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
            }
            1, 2 -> ReplyDecodeResult(
                exception = null,
                value = "<spanned charsequence>",
                rawHexHint = null
            )
            else -> {
                // 协议外 kind:不抛,兜底 raw hex,避免误读出乱码
                @Suppress("UNUSED_VARIABLE") val _unused = kind
                ReplyDecodeResult(exception = null, value = null, rawHexHint = rawHexHint(parcel))
            }
        }
    }

    /**
     * [readUtf16String] 的三态返回值:区分"读到合法 null"和"字段截断 / 长度异常"。
     *
     * Parcel 把 `String? = null` 编码成 `length=-1`,这是合法值,UI 应该展示成 `null`;
     * 而字段不够 / 长度越界是真正的解析失败,需要走 raw hex 兜底。旧实现把两者都返回 null,
     * 上层无法区分,导致接口返回 null 时也被显示成 raw hex。
     */
    private sealed class StringReadResult {
        data class Success(val value: String) : StringReadResult()
        object NullValue : StringReadResult()
        object Failure : StringReadResult()
    }

    /**
     * 读 Parcel 风格 UTF-16 字符串:`int32 length`(字符数,-1 = null) + `length * 2B` UTF-16LE +
     * 2B NUL pad,**不**做 4 字节对齐(reply 头里一段一段读,这里只走完字符 + NUL)。
     *
     * - 正常字符串 → [StringReadResult.Success]
     * - length=-1 → [StringReadResult.NullValue](合法 null,不是错误)
     * - 长度越界 / 负数(除 -1) / 字段不够 → [StringReadResult.Failure],调用方降级 raw hex
     */
    private fun readUtf16String(buffer: ByteBuffer): StringReadResult {
        if (buffer.remaining() < 4) return StringReadResult.Failure
        val len = buffer.int
        if (len == -1) return StringReadResult.NullValue
        if (len < 0) return StringReadResult.Failure
        // 上限:防止畸形大值触发巨量分配;reply 中的异常类名 / String 返回值都不会到这量级
        if (len > 4096) return StringReadResult.Failure
        // length * 2 + 2B NUL,用 Long 算避免 Int 溢出
        val needed = len.toLong() * 2L + 2L
        if (buffer.remaining() < needed) return StringReadResult.Failure
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = buffer.short.toInt().toChar()
        }
        // 跳过 NUL terminator(2B)
        buffer.short
        return StringReadResult.Success(String(chars))
    }

    /**
     * 取 parcel 前 [RAW_HEX_HINT_BYTES] 字节的紧凑 hex 字符串(`AB CD EF ...`),供 UI
     * 显示一行简短兜底。空 parcel 返回空串。
     */
    private fun rawHexHint(parcel: ByteArray): String {
        if (parcel.isEmpty()) return ""
        val limit = minOf(parcel.size, RAW_HEX_HINT_BYTES)
        val sb = StringBuilder(limit * 3)
        for (i in 0 until limit) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", parcel[i].toInt() and 0xFF))
        }
        if (parcel.size > limit) sb.append(" …")
        return sb.toString()
    }
}
