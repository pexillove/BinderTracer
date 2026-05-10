package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 6.3.1 第 2-4 档:Java AIDL 三档(Q+/P/O)。
 *
 * 三个版本 header 长度不同:
 *   - Q+:`[4B policy][4B workSource][4B kHeader 'SYST'][4B length][UTF-16 chars + NUL]` (12B header)
 *   - P :`[4B policy][4B workSource][4B length][UTF-16 chars + NUL]` (8B header)
 *   - O :`[4B policy][4B length][UTF-16 chars + NUL]` (4B header)
 *
 * Parcel 是 native LE。每个 decoder 都通过 [isLikelyInterfaceName] 过滤误判 ——
 * 仅靠 length 落在合理区间不够,P/O 在没有 magic 锚点时极易误识别。
 */

private const val MAX_INTERFACE_NAME_CHARS = 4096
private const val MIN_LENGTH = 1

// 'SYST' magic 写到 LE int32: buffer.getInt(8) 读出来就是 0x53595354
private const val SYST_MAGIC: Int = 0x53595354

/**
 * Java 标识符规则的近似:`[A-Za-z0-9._$]`,且至少含一个 `.`(包名分隔符)。
 * P/O 两档没有 magic 锚定,完全靠"长度+字符集+至少一个点"启发式区分接口名 vs 误命中
 * 的乱码字节,这条规则必须保留。
 */
internal fun isLikelyInterfaceName(s: String): Boolean {
    if (!isLikelyInterfaceNameRelaxed(s)) return false
    return s.contains('.')
}

/**
 * 与 [isLikelyInterfaceName] 同样的字符集校验,但**不强制**含 `.`。
 *
 * 用于 [AidlQDecoder]:Q+ header 已经被 'SYST' magic 锚定(误命中概率 1/2^32),
 * 此时 descriptor 即使是短 token(例如小米 Push SDK 的 `"Xiaomi"`、华为 HMS Core
 * 的厂商私有接口)也应当被接受,而不是跌穿到 TargetRefDecoder 兜底显示 `target@0xN`。
 */
internal fun isLikelyInterfaceNameRelaxed(s: String): Boolean {
    if (s.isEmpty()) return false
    for (c in s) {
        if (c == '.' || c == '_' || c == '$') continue
        if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z') continue
        return false
    }
    return true
}

/**
 * 共用工具:从给定 offset 读 int32 length + (length+1)*2 字节 UTF-16 NUL-terminated。
 *
 * @param data        全 parcel 字节
 * @param lenOffset   length 字段所在的 offset(也即 length 的起点)
 * @param requireDot  字符串是否必须含 `.`(包名分隔符)。
 *   - true(默认):P/O 档使用,堵无 magic 锚定时的误识别
 *   - false:Q+ 档使用,SYST magic 已极大降低误判风险,允许短 token descriptor
 * @return            (interfaceName, payloadStart) 或 null
 */
internal fun readUtf16NameAt(
    data: ByteArray,
    lenOffset: Int,
    requireDot: Boolean = true
): Pair<String, Int>? {
    if (lenOffset < 0 || lenOffset + 4 > data.size) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val length = buffer.getInt(lenOffset)
    if (length < MIN_LENGTH || length > MAX_INTERFACE_NAME_CHARS) return null
    val charsBytes = (length + 1) * 2  // 含 NUL
    val charsStart = lenOffset + 4
    val required = charsStart.toLong() + charsBytes
    if (required > data.size) return null

    val chars = CharArray(length)
    val charsBuf = ByteBuffer.wrap(data, charsStart, length * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until length) {
        chars[i] = charsBuf.short.toInt().toChar()
    }
    val name = String(chars)
    val ok = if (requireDot) isLikelyInterfaceName(name) else isLikelyInterfaceNameRelaxed(name)
    if (!ok) return null

    val charsPaddedBytes = (charsBytes + 3) and 0x3.inv()
    val payloadStart = charsStart + charsPaddedBytes
    return name to payloadStart
}

/**
 * Android Q+(主流):offset 8 处是 magic 'SYST',header 12B,offset 12 起是 length。
 * 命中即 HIGH 置信,这是当前最准确的格式。
 *
 * 字符校验走 [isLikelyInterfaceNameRelaxed](即不强制 `.`),覆盖小米 Push SDK / 华为
 * HMS / 各厂商私有 AIDL 用短 token descriptor 的场景(例如 `"Xiaomi"`)。SYST magic
 * 已极大降低误命中风险(1/2^32),descriptor 字符集合法即认为有效。
 */
class AidlQDecoder : ParcelDecoder {
    override fun tryDecode(event: BinderEvent): DecodeResult? {
        val data = event.rawParcel
        if (data.size < 16) return null
        val magic = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        if (magic != SYST_MAGIC) return null
        val (name, payloadStart) = readUtf16NameAt(data, lenOffset = 12, requireDot = false)
            ?: return null
        return DecodeResult(
            interfaceName = name,
            methodHint = null,
            payloadStart = payloadStart,
            source = DecodeSource.AIDL_Q,
            confidence = Confidence.HIGH
        )
    }
}

/**
 * Android P:8B header(没有 SYST magic),offset 8 起是 length,offset 12 起是 UTF-16 chars。
 * 没有 magic 锚点,完全靠 length + 字符集校验,标 MEDIUM。
 */
class AidlPDecoder : ParcelDecoder {
    override fun tryDecode(event: BinderEvent): DecodeResult? {
        val data = event.rawParcel
        if (data.size < 12) return null
        val (name, payloadStart) = readUtf16NameAt(data, lenOffset = 8) ?: return null
        return DecodeResult(
            interfaceName = name,
            methodHint = null,
            payloadStart = payloadStart,
            source = DecodeSource.AIDL_P,
            confidence = Confidence.MEDIUM
        )
    }
}

/**
 * Android O 及更早:4B header(只有 policy,没有 work source),offset 4 起是 length,
 * offset 8 起是 UTF-16 chars。MEDIUM 置信。
 */
class AidlODecoder : ParcelDecoder {
    override fun tryDecode(event: BinderEvent): DecodeResult? {
        val data = event.rawParcel
        if (data.size < 8) return null
        val (name, payloadStart) = readUtf16NameAt(data, lenOffset = 4) ?: return null
        return DecodeResult(
            interfaceName = name,
            methodHint = null,
            payloadStart = payloadStart,
            source = DecodeSource.AIDL_O,
            confidence = Confidence.MEDIUM
        )
    }
}
