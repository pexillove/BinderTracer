package com.btrace.viewer.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * spec § 6.5 / § 3.2.8:Direction.infer 的判定矩阵。
 *
 * 维度:senderUid 是否命中 targetUid × toUid 是否命中 × isReply × targetUid 是否设过。
 * 重点覆盖:
 *   - targetUid <= 0 时一律 UNKNOWN(不依赖 sender/to)
 *   - toUid == 0 时(BPF 暂未填,本期常态)入向不可判,fallback 到 OUTGOING / INCOMING_REPLY
 *   - reply 帧的 sender/to 语义与 request 镜像
 */
class DirectionTest {

    private val targetUid = 10086

    @Test
    fun `targetUid less or equal zero returns UNKNOWN`() {
        assertEquals(
            Direction.UNKNOWN,
            Direction.infer(senderUid = 10086, toUid = 1000, isReply = false, targetUid = 0)
        )
        assertEquals(
            Direction.UNKNOWN,
            Direction.infer(senderUid = 10086, toUid = 1000, isReply = true, targetUid = -1)
        )
    }

    @Test
    fun `outgoing request when sender hits target`() {
        assertEquals(
            Direction.OUTGOING_REQUEST,
            Direction.infer(senderUid = targetUid, toUid = 1000, isReply = false, targetUid = targetUid)
        )
    }

    @Test
    fun `incoming request when toUid hits and sender does not`() {
        assertEquals(
            Direction.INCOMING_REQUEST,
            Direction.infer(senderUid = 1000, toUid = targetUid, isReply = false, targetUid = targetUid)
        )
    }

    @Test
    fun `incoming request prefers toUid over sender mismatch`() {
        // sender 不命中 + toUid 命中 → 入向(spec § 3.2.8)
        assertEquals(
            Direction.INCOMING_REQUEST,
            Direction.infer(senderUid = 9999, toUid = targetUid, isReply = false, targetUid = targetUid)
        )
    }

    @Test
    fun `outgoing reply when reply and sender hits`() {
        // reply 帧:sender 是原 callee → 目标 App 在回应,出向回复
        assertEquals(
            Direction.OUTGOING_REPLY,
            Direction.infer(senderUid = targetUid, toUid = 1000, isReply = true, targetUid = targetUid)
        )
    }

    @Test
    fun `incoming reply when reply and toUid hits`() {
        assertEquals(
            Direction.INCOMING_REPLY,
            Direction.infer(senderUid = 1000, toUid = targetUid, isReply = true, targetUid = targetUid)
        )
    }

    @Test
    fun `incoming reply fallback when reply but neither hits and toUid is zero`() {
        // BPF 没填 to_uid,reply 又不命中 sender → 兜底为 INCOMING_REPLY
        // (P3 既有行为:reply 帧默认按"我收到回复"显示)
        assertEquals(
            Direction.INCOMING_REPLY,
            Direction.infer(senderUid = 1000, toUid = 0, isReply = true, targetUid = targetUid)
        )
    }

    @Test
    fun `outgoing request fallback when neither sender nor toUid hits and not reply`() {
        // 双向过滤兜底:本期不应出现,但写进逻辑防退化
        assertEquals(
            Direction.OUTGOING_REQUEST,
            Direction.infer(senderUid = 1000, toUid = 2000, isReply = false, targetUid = targetUid)
        )
    }

    @Test
    fun `toUid zero means no INCOMING_REQUEST decision`() {
        // 即使 sender 也不命中,toUid=0 也不能判 INCOMING(避免误标)
        // → fallback 到 OUTGOING_REQUEST
        assertEquals(
            Direction.OUTGOING_REQUEST,
            Direction.infer(senderUid = 9999, toUid = 0, isReply = false, targetUid = targetUid)
        )
    }

    @Test
    fun `sender hit takes priority over toUid hit on request`() {
        // 双命中(理论上 sender == to == target,系统服务自调?)→ 视为 OUTGOING
        assertEquals(
            Direction.OUTGOING_REQUEST,
            Direction.infer(senderUid = targetUid, toUid = targetUid, isReply = false, targetUid = targetUid)
        )
    }

    @Test
    fun `sender hit takes priority over toUid hit on reply`() {
        assertEquals(
            Direction.OUTGOING_REPLY,
            Direction.infer(senderUid = targetUid, toUid = targetUid, isReply = true, targetUid = targetUid)
        )
    }
}
