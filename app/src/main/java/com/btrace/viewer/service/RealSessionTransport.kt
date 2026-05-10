package com.btrace.viewer.service

import com.btrace.viewer.data.EventRepository
import com.btrace.viewer.data.SocketClient
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.AppClassLoaderRegistry
import com.btrace.viewer.parser.InterfaceIndexBuilder
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.root.BTraceManager
import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 把 AppsViewModel.startMonitoring 的步骤 0-5 与 MonitorViewModel.startEventCollection /
 * startHeartbeat 的运行期循环串成会话需要的那批 suspend 接口。
 *
 * 实现细节直接照搬两个 ViewModel 原版,所有日志、错误处理一起搬过来,以便 review-diff 时
 * 易于核对"这部分行为没变,只是搬家"。
 */
@Singleton
class RealSessionTransport @Inject constructor(
    private val socketClient: SocketClient,
    private val btraceManager: BTraceManager,
    private val classLoaderRegistry: AppClassLoaderRegistry,
    private val methodResolver: MethodResolver,
    private val eventRepository: EventRepository,
    private val interfaceIndexBuilder: InterfaceIndexBuilder,
    private val serviceManagerCatalog: com.btrace.viewer.parser.ServiceManagerCatalog,
) : SessionTransport {

    companion object { private const val TAG = "RealSessionTransport" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null

    override suspend fun stopExistingDaemon() {
        btraceManager.stopBTrace()
    }

    override suspend fun disconnectSocket() {
        socketClient.disconnect()
    }

    override suspend fun startServer(): Boolean {
        return socketClient.startServer(btraceManager.getListenHost(), btraceManager.getListenPort())
    }

    override suspend fun startDaemon(uid: Int, sessionToken: String): Boolean {
        return btraceManager.startBTrace(
            BTraceManager.DEFAULT_BTRACE_PATH,
            btraceManager.getListenAddr(),
            uid,
            sessionToken
        )
    }

    override suspend fun awaitClient(): Boolean = socketClient.awaitClient()

    override suspend fun handshake(uid: Int, sessionToken: String): Boolean {
        return socketClient.sendSetTargetAndAwaitAck(
            uid = uid, expectedSessionToken = sessionToken, timeoutMs = 2000
        )
    }

    override suspend fun preheatClassLoader(packageName: String) {
        // review 2026-05-02 P1:
        //   1) 先 await prepareAsync(callback 风格 → 包成 suspend),拿到就绪状态;
        //   2) 成功后串行 scanPackageAsync 把这个包补进 InterfaceIndex,避免新包的接口名
        //      在下次全量 scan 之前一直 miss;
        //   3) 串行调用是为了避免和后续解析事件竞态(fire-and-forget 会导致首批事件查表 miss);
        //   4) scanPackageAsync 失败不阻塞 preheat —— 它内部已 catch,这里再加一层兜底。
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            classLoaderRegistry.prepareAsync(packageName) { success ->
                if (cont.isActive) cont.resume(success)
            }
        }
        if (ok) {
            CLogUtils.i(TAG, "ClassLoader 就绪,失效 $packageName 的旧签名缓存")
            methodResolver.invalidatePackage(packageName)
            try {
                interfaceIndexBuilder.scanPackageAsync(packageName)
            } catch (t: Throwable) {
                // scanPackageAsync 内部已 catch 大多数路径,这里只是兜底,绝不让 preheat 失败
                CLogUtils.w(TAG, "scanPackageAsync($packageName) 兜底异常: ${t.message}", t)
            }
        }
        // ServiceManagerCatalog 同步 warmup:跑 service list + 反射所有 framework Stub。
        // 用户选择"开始监控按下后同步拉一次",首条 target@0xN 即可命中候选反查。
        // catalog 内部已 catch 所有异常 + 自带 mutex 去重,失败不阻塞 preheat。
        try {
            serviceManagerCatalog.warmup()
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "ServiceManagerCatalog.warmup 兜底异常: ${t.message}", t)
        }
        CLogUtils.i(TAG, "ClassLoader 预热完成: $packageName, ready=$ok")
    }

    override suspend fun sendPause(): Boolean = socketClient.sendPause()
    override suspend fun sendResume(): Boolean = socketClient.sendResume()
    override suspend fun sendShutdown(): Boolean = socketClient.sendShutdown()

    override suspend fun startEventLoop(onError: (String) -> Unit) {
        eventJob?.cancel()
        heartbeatJob?.cancel()

        eventJob = scope.launch {
            socketClient.eventFlow().collect { frame ->
                when (frame.type) {
                    SocketClient.MSG_BINDER_EVENT -> {
                        val event = BinderEvent.fromPayload(frame.payload) ?: return@collect
                        eventRepository.addEvent(event)
                    }
                    SocketClient.MSG_ERROR -> {
                        val errorCode = if (frame.payload.size >= 4)
                            ByteBuffer.wrap(frame.payload).int else 0
                        onError("btrace 错误: $errorCode")
                    }
                    else -> Unit
                }
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (!socketClient.isConnected()) continue
                socketClient.sendPing()
            }
        }
    }

    override suspend fun stopEventLoop() {
        eventJob?.cancel(); eventJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
    }

    override suspend fun clearPreviousEvents() {
        eventRepository.clearEvents()
    }

    override fun isSocketConnected(): Boolean = socketClient.isConnected()
}
