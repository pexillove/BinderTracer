package com.btrace.viewer.service

import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 监控会话协调层(review #3)。
 *
 * 把启动握手 / 暂停 / 恢复 / 停止 / 错误上报五件事收到一处,UI 只看 [state] / [errorMessage] /
 * [targetAppName] 三个 StateFlow,所有动作通过 suspend 接口下发。
 *
 * 不持有 Android Context、不直接调 root,所有副作用走 [SessionTransport],便于单测。
 */
class MonitoringSessionController(
    private val transport: SessionTransport,
    backgroundDispatcher: CoroutineDispatcher
) {
    companion object { private const val TAG = "MonitoringSessionController" }

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private val _state = MutableStateFlow(MonitoringSessionState.IDLE)
    val state: StateFlow<MonitoringSessionState> = _state.asStateFlow()

    private val _targetAppName = MutableStateFlow("")
    val targetAppName: StateFlow<String> = _targetAppName.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile private var sessionToken: String = ""
    @Volatile private var targetUid: Int = -1

    fun startMonitoring(targetUid: Int, packageName: String, appName: String): Job {
        return scope.launch {
            if (_state.value != MonitoringSessionState.IDLE && _state.value != MonitoringSessionState.FAILED) {
                CLogUtils.w(TAG, "startMonitoring() ignored, current=${_state.value}")
                return@launch
            }
            _targetAppName.value = appName
            _errorMessage.value = null
            this@MonitoringSessionController.targetUid = targetUid

            transition(MonitoringSessionState.STARTING)

            // 切换 App 后立即清空上一会话事件 / 覆盖率,避免新会话事件追加在旧事件后面。
            // 放在 STARTING 之后、daemon 拉起之前,UI 第一时间反映"全新会话";不放在 stop()
            // 路径里,因为 service 崩溃 / 异常停止时不会触发,从新会话清才稳。
            transport.clearPreviousEvents()

            transport.disconnectSocket()
            transport.stopExistingDaemon()

            if (!transport.startServer()) {
                fail("TCP Server 启动失败")
                return@launch
            }
            sessionToken = generateSessionToken()
            if (!transport.startDaemon(targetUid, sessionToken)) {
                fail("btrace 启动失败,请检查 Root 权限")
                transport.disconnectSocket()
                return@launch
            }
            if (!transport.awaitClient()) {
                fail("TCP 连接失败")
                transport.disconnectSocket()
                transport.stopExistingDaemon()
                return@launch
            }

            transition(MonitoringSessionState.HANDSHAKING)
            if (!transport.handshake(targetUid, sessionToken)) {
                fail("与 btrace 握手失败")
                transport.disconnectSocket()
                transport.stopExistingDaemon()
                return@launch
            }

            transport.preheatClassLoader(packageName)
            transport.startEventLoop { msg -> _errorMessage.value = msg }

            transition(MonitoringSessionState.CONNECTED)
        }
    }

    fun pause(): Job = scope.launch {
        if (_state.value != MonitoringSessionState.CONNECTED) return@launch
        if (transport.sendPause()) {
            transition(MonitoringSessionState.PAUSED)
        }
    }

    fun resume(): Job = scope.launch {
        if (_state.value != MonitoringSessionState.PAUSED) return@launch
        if (transport.sendResume()) {
            transition(MonitoringSessionState.CONNECTED)
        }
    }

    fun stop(): Job = scope.launch {
        if (_state.value == MonitoringSessionState.IDLE) return@launch
        transition(MonitoringSessionState.STOPPING)
        transport.sendShutdown()
        transport.stopEventLoop()
        transport.disconnectSocket()
        transport.stopExistingDaemon()
        _targetAppName.value = ""
        targetUid = -1
        sessionToken = ""
        transition(MonitoringSessionState.IDLE)
    }

    /** 由 Section 5 的重连流程调用。 */
    fun enterReconnecting() {
        if (_state.value == MonitoringSessionState.CONNECTED ||
            _state.value == MonitoringSessionState.PAUSED) {
            transition(MonitoringSessionState.RECONNECTING)
        }
    }

    /**
     * Section 5 重连流程入口。RECONNECTING 态下发起最多 [maxAttempts] 轮
     * server/daemon/handshake/event-loop 重启,任一轮成功 → CONNECTED;全部失败 → FAILED。
     */
    fun attemptReconnect(maxAttempts: Int = 3, intervalMs: Long = 5000): Job = scope.launch {
        if (_state.value != MonitoringSessionState.RECONNECTING) return@launch
        repeat(maxAttempts) { i ->
            CLogUtils.i(TAG, "attemptReconnect() try ${i + 1}/$maxAttempts")
            transport.disconnectSocket()
            if (transport.startServer() &&
                transport.startDaemon(targetUid, sessionToken) &&
                transport.awaitClient() &&
                transport.handshake(targetUid, sessionToken)) {
                transport.startEventLoop { msg -> _errorMessage.value = msg }
                transition(MonitoringSessionState.CONNECTED)
                return@launch
            }
            kotlinx.coroutines.delay(intervalMs)
        }
        fail("自动重连失败")
    }

    fun reset() {
        if (_state.value == MonitoringSessionState.FAILED) {
            transition(MonitoringSessionState.IDLE)
            _errorMessage.value = null
        }
    }

    fun isSocketConnected(): Boolean = transport.isSocketConnected()

    private fun transition(next: MonitoringSessionState) {
        CLogUtils.i(TAG, "transition ${_state.value} -> $next")
        _state.value = next
    }

    private fun fail(message: String) {
        _errorMessage.value = message
        transition(MonitoringSessionState.FAILED)
    }

    private fun generateSessionToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
