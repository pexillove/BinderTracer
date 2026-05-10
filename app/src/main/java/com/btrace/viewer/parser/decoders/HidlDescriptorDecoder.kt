package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.BinderDev

/**
 * spec § 6.3.1 第 5 档 + § 3.2.1:HIDL / HwBinder。
 *
 * HIDL parcel 起点没有 12B header,接口描述符以 UTF-8 ASCII 直接写在 offset 0,
 * 形如 `<package>@<x.y>::<Interface>`(例 `android.hardware.graphics.allocator@4.0::IAllocator`)。
 *
 * 触发条件(任一):
 *   1. [BinderDev.HWBINDER] —— 来自 BPF 端的 binder_dev 字段(本期 stub 总是 UNKNOWN,
 *      所以条件 2 是主路径,但代码路径完整保留供后续启用)
 *   2. ASCII 形式同时含 `@` 和 `::`
 *
 * 双重特征验证(`@` + `::`)杜绝随机 ASCII 误命中。
 */
class HidlDescriptorDecoder : ParcelDecoder {

    override fun tryDecode(event: BinderEvent): DecodeResult? {
        val data = event.rawParcel
        if (data.isEmpty()) return null

        val ascii = readAsciiUntilNul(data, MAX_DESCRIPTOR_LEN)
        val hasShape = ascii.contains('@') && ascii.contains("::")

        // binderDev 强信号:即便 ASCII 特征不全也可命中(本期 stub 总走不到这里,留路径)
        val devHint = event.binderDev == BinderDev.HWBINDER

        if (!devHint && !hasShape) return null
        if (ascii.length < MIN_DESCRIPTOR_LEN || !hasShape) {
            // binder_dev 标了 HWBINDER 但描述符特征看不到,放弃 —— 让流水线继续走启发式档
            return null
        }

        val confidence = if (devHint) Confidence.HIGH else Confidence.MEDIUM
        return DecodeResult(
            interfaceName = ascii,
            methodHint = null,
            payloadStart = -1,
            source = DecodeSource.HIDL_DESCRIPTOR,
            confidence = confidence
        )
    }

    /**
     * 从 offset 0 起读可见 ASCII (0x20..0x7E),遇到 NUL 或非可见字符即终止。
     * 上限 [maxLen] 防御畸形数据。
     */
    private fun readAsciiUntilNul(data: ByteArray, maxLen: Int): String {
        val limit = minOf(data.size, maxLen)
        val sb = StringBuilder()
        for (i in 0 until limit) {
            val b = data[i].toInt() and 0xFF
            if (b in 0x20..0x7E) {
                sb.append(b.toChar())
            } else {
                break
            }
        }
        return sb.toString()
    }

    companion object {
        // HIDL 描述符典型在 30~80 字节之间,256 给足余量
        private const val MAX_DESCRIPTOR_LEN = 256

        // 最短形如 `a@1.0::Ix` = 9 字节,定 8 已经过紧
        private const val MIN_DESCRIPTOR_LEN = 8
    }
}
