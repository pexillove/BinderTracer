package com.btrace.viewer.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.btrace.viewer.data.EventRepository
import com.btrace.viewer.data.SettingsRepository
import com.btrace.viewer.overlay.OverlayController
import com.btrace.viewer.overlay.OverlayPermissionHelper
import com.btrace.viewer.overlay.OverlayUiState
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 前台监控服务。生命周期 = 一次完整的监控会话(启动 → CONNECTED → 停止)。
 *
 * - 仍是单进程内的本地 Service,UI 通过 [LocalBinder] bind 后拿到 [MonitoringSessionController]
 *   句柄并直接订阅它的 StateFlow。
 * - 启动用 startForegroundService() + startForeground()(5s 内必须 startForeground)。
 * - 停止用 stopSelf();bind/unbind 不影响 foreground 状态。
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    companion object {
        private const val TAG = "MonitoringService"
        const val ACTION_START = "com.btrace.viewer.service.START"
        const val ACTION_STOP = "com.btrace.viewer.service.STOP"
    }

    @Inject lateinit var controller: MonitoringSessionController
    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var overlayController: OverlayController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var watchdogJob: Job? = null
    private var overlayJob: Job? = null

    inner class LocalBinder : Binder() {
        fun controller(): MonitoringSessionController = controller
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        MonitoringNotification.ensureChannel(this)
        CLogUtils.i(TAG, "onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立刻把自己升前台,避免 ANR。文案先用 IDLE 占位,后面订阅 state 后会刷新。
        val initial = MonitoringNotification.build(
            this, "BinderTracer 监控", "正在准备…"
        )
        ServiceCompat.startForeground(
            this,
            MonitoringNotification.NOTIFICATION_ID,
            initial,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        startNotificationUpdates()
        startWatchdog()
        startOverlayUpdates()

        when (intent?.action) {
            ACTION_STOP -> {
                CLogUtils.i(TAG, "onStartCommand() ACTION_STOP")
                scope.launch { controller.stop().join(); stopSelfSafely() }
            }
            else -> CLogUtils.d(TAG, "onStartCommand() action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        CLogUtils.i(TAG, "onDestroy()")
        notificationJob?.cancel()
        watchdogJob?.cancel()
        overlayJob?.cancel()
        overlayController.hide()
        super.onDestroy()
    }

    /**
     * 悬浮窗驱动:监听设置开关 + 监控状态 + app 前后台。
     * - 开启 + CONNECTED/PAUSED/RECONNECTING + 有 SYSTEM_ALERT_WINDOW 权限 + app 在后台 → show
     * - 其它情况(IDLE / 关闭设置 / 无权限 / app 已在前台) → hide
     * - update() 跟着 eventCount/eventRate/recentEvents 滚动更新
     *
     * "app 在前台时自动隐藏"是为了避免悬浮窗挡主界面 —— 用户已经看得到 MonitorScreen,
     * bubble 没必要再叠一层;切到其它 app 才需要 bubble 提示。前后台用 ProcessLifecycleOwner
     * 判定:ON_START → 前台,ON_STOP → 后台。
     */
    private fun startOverlayUpdates() {
        overlayJob?.cancel()
        overlayJob = scope.launch {
            combine(
                settingsRepository.overlayEnabledFlow,
                controller.state,
                controller.targetAppName,
                eventRepository.eventCount,
                eventRepository.eventRate,
                appForegroundFlow()
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                OverlayParams(
                    enabled = values[0] as Boolean,
                    state = values[1] as MonitoringSessionState,
                    app = values[2] as String,
                    count = values[3] as Int,
                    rate = values[4] as Int,
                    inForeground = values[5] as Boolean
                )
            }.distinctUntilChanged().collect { p ->
                val shouldShow = p.enabled &&
                    p.state in setOf(
                        MonitoringSessionState.CONNECTED,
                        MonitoringSessionState.PAUSED,
                        MonitoringSessionState.RECONNECTING
                    ) &&
                    OverlayPermissionHelper.canDraw(this@MonitoringService) &&
                    !p.inForeground

                if (shouldShow) {
                    if (!overlayController.isShowing()) overlayController.show()
                } else if (overlayController.isShowing()) {
                    overlayController.hide()
                }

                if (overlayController.isShowing()) {
                    overlayController.update(
                        OverlayUiState(
                            targetAppName = p.app,
                            eventCount = p.count,
                            eventRate = p.rate,
                            recentEvents = eventRepository.filteredEvents.value
                        )
                    )
                }
            }
        }
    }

    /**
     * app 级前后台 Flow。ProcessLifecycleOwner 必须在主线程访问 —— 调用方
     * (startOverlayUpdates)的 scope 已经是 Dispatchers.Main.immediate,因此
     * callbackFlow 内部的 addObserver / removeObserver 都安全。初始值用 currentState
     * 探测,避免观察者注册前就漏掉一次状态。
     */
    private fun appForegroundFlow(): Flow<Boolean> = callbackFlow {
        val owner = ProcessLifecycleOwner.get()
        trySend(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> trySend(true)
                Lifecycle.Event.ON_STOP -> trySend(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        awaitClose { owner.lifecycle.removeObserver(observer) }
    }

    private data class OverlayParams(
        val enabled: Boolean,
        val state: MonitoringSessionState,
        val app: String,
        val count: Int,
        val rate: Int,
        val inForeground: Boolean
    )

    /**
     * 心跳 watchdog(review #1b):10s 一次,CONNECTED 态下检查 socket 是否还活着。
     * 连续 3 次 socket-down → controller.enterReconnecting() + attemptReconnect。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.Default) {
            var consecutiveMisses = 0
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val state = controller.state.value
                if (state != MonitoringSessionState.CONNECTED) {
                    consecutiveMisses = 0
                    continue
                }
                val alive = controller.isSocketConnected()
                if (alive) {
                    consecutiveMisses = 0
                } else {
                    consecutiveMisses++
                    CLogUtils.w(TAG, "watchdog: socket down, miss=$consecutiveMisses/3")
                    if (consecutiveMisses >= 3) {
                        controller.enterReconnecting()
                        controller.attemptReconnect(maxAttempts = 3, intervalMs = 5000)
                        consecutiveMisses = 0
                    }
                }
            }
        }
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            combine(
                controller.state, controller.targetAppName,
                eventRepository.eventCount, eventRepository.eventRate
            ) { state, app, count, rate -> Quadruple(state, app, count, rate) }
                .distinctUntilChanged()
                .collect { (state, app, count, rate) ->
                    val title = when (state) {
                        MonitoringSessionState.IDLE -> "BinderTracer"
                        MonitoringSessionState.STARTING,
                        MonitoringSessionState.HANDSHAKING -> "正在启动 $app"
                        MonitoringSessionState.CONNECTED -> "监控中: $app"
                        MonitoringSessionState.PAUSED -> "已暂停: $app"
                        MonitoringSessionState.RECONNECTING -> "重连中: $app"
                        MonitoringSessionState.FAILED -> "监控失败: $app"
                        MonitoringSessionState.STOPPING -> "正在停止"
                    }
                    val body = "事件 $count · $rate/s"
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.notify(
                        MonitoringNotification.NOTIFICATION_ID,
                        MonitoringNotification.build(this@MonitoringService, title, body)
                    )

                    if (state == MonitoringSessionState.IDLE) {
                        stopSelfSafely()
                    }
                }
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
