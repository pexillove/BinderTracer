package com.btrace.viewer.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 把 Android 的 bindService 回调式 API 包成 suspend 接口。
 * UI(ViewModel)调 [ensureBoundAndStart] / [requestStop] 即可,不再直接调 SocketClient/BTraceManager。
 */
@Singleton
class MonitoringServiceConnector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "MonitoringServiceConnector" }

    @Volatile private var binder: MonitoringService.LocalBinder? = null
    @Volatile private var bound: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            CLogUtils.i(TAG, "onServiceConnected $name")
            binder = service as? MonitoringService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            CLogUtils.w(TAG, "onServiceDisconnected $name")
            binder = null
        }
    }

    /**
     * 启动并 bind Service,挂起直到 onServiceConnected 回调,然后调 controller.startMonitoring。
     */
    suspend fun ensureBoundAndStart(targetUid: Int, packageName: String, appName: String) {
        ensureBound()
        binder?.controller()?.startMonitoring(targetUid, packageName, appName)
    }

    suspend fun ensureBound(): MonitoringSessionController {
        binder?.let { return it.controller() }
        return suspendCancellableCoroutine { cont ->
            val once = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val b = service as MonitoringService.LocalBinder
                    binder = b
                    bound = true
                    cont.resume(b.controller())
                }
                override fun onServiceDisconnected(name: ComponentName?) { binder = null }
            }
            val intent = Intent(context, MonitoringService::class.java)
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, once, Context.BIND_AUTO_CREATE)
            cont.invokeOnCancellation { runCatching { context.unbindService(once) } }
        }
    }

    fun requestStop() {
        val intent = Intent(context, MonitoringService::class.java)
            .setAction(MonitoringService.ACTION_STOP)
        ContextCompat.startForegroundService(context, intent)
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
        binder = null
    }
}
