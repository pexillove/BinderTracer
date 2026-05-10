package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent

/**
 * spec § 6.3.1 探测器流水线统一接口。每个 decoder 对一个特征做非破坏性 peek,
 * 命中返回 [DecodeResult],未命中返回 null 让流水线 yield 下一档。
 */
interface ParcelDecoder {
    fun tryDecode(event: BinderEvent): DecodeResult?
}

/**
 * 探测器输出。
 *
 * @param interfaceName 接口名,可能是 "(reply)" / "(orphan)" / "android.app.IFoo" / "target@0xXXXX"
 * @param methodHint    部分探测器(SpecialCode/Reply/TargetRef)能直接给 method 名;
 *                      AIDL 类档不给,留给 MethodResolver。
 * @param payloadStart  后续参数解码的起点 offset(主要供 ParcelArgumentDecoder 用,
 *                      AIDL 各档给真正的 arg 起点;HIDL/Reply/Special 等没有 AIDL 参数语义,给 -1)
 * @param source        哪个 decoder 命中,供覆盖率统计
 * @param confidence    解析置信度
 */
data class DecodeResult(
    val interfaceName: String,
    val methodHint: String?,
    val payloadStart: Int,
    val source: DecodeSource,
    val confidence: Confidence
)

enum class DecodeSource {
    REPLY,
    SPECIAL_CODE,
    AIDL_Q,
    AIDL_P,
    AIDL_O,
    HIDL_DESCRIPTOR,
    RAW_ASCII,
    TARGET_REF
}

enum class Confidence { HIGH, MEDIUM, LOW }
