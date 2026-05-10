package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent

/**
 * spec § 6.3.1 第 6 档:启发式裸 ASCII 探测器。
 *
 * 兜底于 HIDL 之后:有些私有协议 / 老服务直接把接口名写成 ASCII 但不带 HIDL 的 `@x.y::I*` 形式。
 * 弱启发,标 LOW,在显式格式都 miss 后才接管。
 *
 * 触发条件:offset 0 起读出 ≥ 8 字节可见 ASCII,且包含 `.`(过滤纯字母乱码字节)。
 * 多空格、Tab、其他控制字符提前终止。
 */
class RawAsciiHeuristicDecoder : ParcelDecoder {

    override fun tryDecode(event: BinderEvent): DecodeResult? {
        val data = event.rawParcel
        if (data.size < MIN_LEN) return null

        val sb = StringBuilder()
        for (i in 0 until minOf(data.size, MAX_LEN)) {
            val b = data[i].toInt() and 0xFF
            // 0x20 (space) 也终止 —— 接口名里不会有空格,出现空格说明不是接口名
            if (b in 0x21..0x7E) {
                sb.append(b.toChar())
            } else {
                break
            }
        }
        if (sb.length < MIN_LEN) return null
        if (!sb.contains('.')) return null

        return DecodeResult(
            interfaceName = sb.toString(),
            methodHint = null,
            payloadStart = -1,
            source = DecodeSource.RAW_ASCII,
            confidence = Confidence.LOW
        )
    }

    companion object {
        private const val MIN_LEN = 8
        private const val MAX_LEN = 256
    }
}
