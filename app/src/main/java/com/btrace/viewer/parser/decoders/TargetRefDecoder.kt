package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.BinderHandleResolver

/**
 * spec § 6.3.1 第 7 档:终极兜底,确保事件**永远不消失**。
 *
 * 上面所有探测器都 miss 时(无 token 裸 parcel / 全乱码 / 空 parcel),退化到展示
 * 目标引用值。
 *
 * 增强(2026-05-05):若 [resolver] 已建好 (senderPid, handle) → ownerProcess 表,
 * 把 raw `target@0xN` 升级为 `target@0xN → <ownerProcess>` 提供可读上下文。命中
 * 与否完全 best-effort:resolver 还没 refresh 完 / handle 是 monitor 启动后新分配
 * 还没被快照覆盖时,fallback 仍展示 raw `target@0xN`,行为与升级前完全一致。
 */
class TargetRefDecoder(private val resolver: BinderHandleResolver? = null) : ParcelDecoder {
    override fun tryDecode(event: BinderEvent): DecodeResult {
        val ifaceName = when {
            event.targetRef == 0L -> "unknown"
            else -> {
                val raw = "target@0x${event.targetRef.toString(16).uppercase()}"
                val resolved = resolver?.lookup(event.pid, event.targetRef)
                if (resolved != null) "$raw → ${resolved.processName}" else raw
            }
        }
        return DecodeResult(
            interfaceName = ifaceName,
            methodHint = "code=${event.code}",
            payloadStart = -1,
            source = DecodeSource.TARGET_REF,
            confidence = Confidence.LOW
        )
    }
}
