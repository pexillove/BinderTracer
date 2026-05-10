package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent

/**
 * spec § 6.3.1 第 1 档 + § 3.2.3:协议固定保留 code 表。
 *
 * 任何 Binder 接口都共享一组保留 code,parcel 要么为空要么是定制格式,**永远不写 interfaceToken**,
 * 应当直接靠 code 值命名。
 *
 * 注意:_NTF (INTERFACE_TRANSACTION) 在 spec § 3.2.3 表里写成 0x5F494E54 是 typo
 * (那是 _INT 不是 _NTF),正确值由下面的 [asciiCode] 计算得 0x5F4E5446。这里全部从 ASCII
 * 算式生成,杜绝传抄错误。
 */
class SpecialCodeDecoder : ParcelDecoder {

    override fun tryDecode(event: BinderEvent): DecodeResult? {
        val name = SPECIAL_CODES[event.code] ?: return null
        return DecodeResult(
            interfaceName = "<protocol>",
            methodHint = name,
            payloadStart = -1,
            source = DecodeSource.SPECIAL_CODE,
            confidence = Confidence.HIGH
        )
    }

    companion object {
        private fun asciiCode(a: Char, b: Char, c: Char, d: Char): Int =
            (a.code shl 24) or (b.code shl 16) or (c.code shl 8) or d.code

        // 用 ASCII 计算式表达,与 AOSP IBinder.h 一致;对 spec § 3.2.3 中表格的 hex 值不依赖。
        private val SPECIAL_CODES: Map<Int, String> = mapOf(
            asciiCode('_', 'P', 'N', 'G') to "PING_TRANSACTION",
            asciiCode('_', 'D', 'M', 'P') to "DUMP_TRANSACTION",
            asciiCode('_', 'C', 'M', 'D') to "SHELL_COMMAND_TRANSACTION",
            asciiCode('_', 'N', 'T', 'F') to "INTERFACE_TRANSACTION",
            asciiCode('_', 'S', 'P', 'R') to "SYSPROPS_TRANSACTION",
            asciiCode('_', 'E', 'X', 'T') to "EXTENSION_TRANSACTION",
            0x00FFFFFE to "HIDL_GET_DESCRIPTOR_TRANSACTION",
            0x00FFFFFD to "HIDL_DESCRIPTOR_CHAIN_TRANSACTION",
            0x00FFFFFC to "HIDL_PING_TRANSACTION",
            0x00FFFFFF to "HIDL_LINK_TO_DEATH_TRANSACTION"
        )
    }
}
