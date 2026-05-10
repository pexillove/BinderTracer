package com.btrace.viewer.service

/**
 * 监控会话状态机(review #1a)。
 *
 *   IDLE
 *    │ startMonitoring()
 *    ▼
 *   STARTING ── 失败 ──▶ FAILED
 *    │ daemon up & socket bound
 *    ▼
 *   HANDSHAKING ── 失败 ──▶ FAILED
 *    │ ack received
 *    ▼
 *   CONNECTED ◀──▶ PAUSED        (用户 pause/resume)
 *    │  socket dropped
 *    ▼
 *   RECONNECTING ── 重连失败 ──▶ FAILED
 *    │  reconnected & re-handshake ok
 *    ▼
 *   CONNECTED
 *
 *   任意状态 + stopMonitoring() ─▶ STOPPING ─▶ IDLE
 *   FAILED + reset() ─▶ IDLE
 */
enum class MonitoringSessionState {
    IDLE,
    STARTING,
    HANDSHAKING,
    CONNECTED,
    PAUSED,
    RECONNECTING,
    FAILED,
    STOPPING
}
