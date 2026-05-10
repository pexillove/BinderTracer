package com.btrace.viewer.model

/**
 * 调用栈反符号化质量(spec 2026-05-03 § 4.4.2)
 */
enum class StackQuality(val raw: Int) {
    FULL(0),       // .symtab 命中
    FP_ONLY(1),    // FP 抓栈但符号未命中,只有模块路径
    DEGRADED(2),   // .dynsym 降级 / 无 symbol 命中
    FAILED(3);     // 抓栈失败 / 进程退出

    companion object {
        fun fromRaw(b: Byte): StackQuality = when (b.toInt() and 0xff) {
            0 -> FULL
            1 -> FP_ONLY
            2 -> DEGRADED
            3 -> FAILED
            else -> DEGRADED
        }
    }
}

/**
 * 单个栈帧(spec § 4.4.2 frame 布局)
 *
 * 详情页长按帧时复制 `module!symbol+0xoffset`,UI 显示也用同一格式。
 */
data class StackFrame(
    val pc: Long,
    val module: String,
    val symbol: String,
    val offset: Long
) {
    /** 显示文本:`libbinder.so!IPCThreadState::transact + 0x4c`(symbol 为空时退化为模块 + 偏移) */
    fun displayText(): String {
        val mod = module.substringAfterLast('/').ifEmpty { module }
        return if (symbol.isNotEmpty()) {
            "$mod!$symbol + 0x${offset.toString(16)}"
        } else if (mod.isNotEmpty()) {
            "$mod + 0x${offset.toString(16)}"
        } else {
            "0x${pc.toString(16)}"
        }
    }
}

/**
 * 调用栈(spec § 4.4.2):内核帧 + 用户帧 + 质量评级 + 截断标志。
 */
data class StackTrace(
    val quality: StackQuality,
    val truncated: Int, // bit 0 = kstack 32 满,bit 1 = ustack 32 满
    val kFrames: List<StackFrame>,
    val uFrames: List<StackFrame>,
    val failureReason: String = ""
) {
    val kstackTruncated: Boolean get() = (truncated and 0x01) != 0
    val ustackTruncated: Boolean get() = (truncated and 0x02) != 0

    val totalDepth: Int get() = kFrames.size + uFrames.size
}
