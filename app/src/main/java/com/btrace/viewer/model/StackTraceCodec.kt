package com.btrace.viewer.model

import java.nio.ByteBuffer

/**
 * STACK_TRACE TLV payload 解码器(spec 2026-05-03 § 4.4.2)。
 *
 * 与 daemon `protocol.go` 的 `EncodeStackTraceTLV / DecodeStackTraceTLV` 严格对齐;
 * 任一字段越界 / 长度异常 → 返回 null(fail-open),**绝不**抛异常,
 * BinderEvent.fromPayload 的 5 级 guard 链已经通过即说明前 8B 头 + 帧数组完整性可读。
 */
object StackTraceCodec {

    /**
     * 解析 [data] 在 [start, start+length) 范围内的 STACK_TRACE TLV payload。
     *
     * 布局(Big Endian,与 daemon 端 `EncodeStackTraceTLV` 严格对齐):
     *
     * ```
     *  off  len  field
     *   0    1B  quality          0=FULL 1=FP_ONLY 2=DEGRADED 3=FAILED
     *   1    1B  truncated        bit 0 = kstack 32 满,bit 1 = ustack 32 满
     *   2    2B  reserved
     *   4    2B  kframe_count
     *   6    2B  uframe_count
     *   8    ... kframe[*] 数组,每帧:
     *              8B pc + 2B module_len + 2B symbol_len + 8B offset
     *              + module bytes + symbol bytes
     *  ...   ... uframe[*] 数组,同上
     *  ...   2B  failure_reason_len
     *  ...   ?B  failure_reason 字节(UTF-8)
     * ```
     */
    fun decode(data: ByteArray, start: Int, length: Int): StackTrace? {
        if (length < 8) return null
        if (start < 0 || start + length > data.size) return null

        val buf = ByteBuffer.wrap(data, start, length).slice() // slice 后 position 从 0 开始
        if (buf.remaining() < 8) return null

        val quality = StackQuality.fromRaw(buf.get())
        val truncated = buf.get().toInt() and 0xff
        buf.short                                        // reserved
        val kCount = (buf.short.toInt() and 0xffff)
        val uCount = (buf.short.toInt() and 0xffff)

        val kFrames = parseFrames(buf, kCount) ?: return null
        val uFrames = parseFrames(buf, uCount) ?: return null

        // failure_reason(可选)
        var failureReason = ""
        if (buf.remaining() >= 2) {
            val rl = buf.short.toInt() and 0xffff
            if (rl in 1..buf.remaining()) {
                val rb = ByteArray(rl)
                buf.get(rb)
                failureReason = String(rb, Charsets.UTF_8)
            }
        }

        return StackTrace(
            quality = quality,
            truncated = truncated,
            kFrames = kFrames,
            uFrames = uFrames,
            failureReason = failureReason
        )
    }

    private fun parseFrames(buf: ByteBuffer, count: Int): List<StackFrame>? {
        if (count == 0) return emptyList()
        val out = ArrayList<StackFrame>(count)
        repeat(count) {
            if (buf.remaining() < 20) return null
            val pc = buf.long
            val mLen = buf.short.toInt() and 0xffff
            val sLen = buf.short.toInt() and 0xffff
            val offset = buf.long
            if (buf.remaining() < mLen + sLen) return null
            val mb = ByteArray(mLen)
            if (mLen > 0) buf.get(mb)
            val sb = ByteArray(sLen)
            if (sLen > 0) buf.get(sb)
            out.add(
                StackFrame(
                    pc = pc,
                    module = String(mb, Charsets.UTF_8),
                    symbol = String(sb, Charsets.UTF_8),
                    offset = offset
                )
            )
        }
        return out
    }
}
