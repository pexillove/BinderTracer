package com.btrace.viewer.service

/**
 * 会话用到的全部外部副作用(socket、root daemon、classloader 预热)收口接口。
 * 抽出来主要为了 [MonitoringSessionController] 单测可以注入 fake。
 */
interface SessionTransport {
    suspend fun stopExistingDaemon()
    suspend fun disconnectSocket()
    suspend fun startServer(): Boolean
    suspend fun startDaemon(uid: Int, sessionToken: String): Boolean
    suspend fun awaitClient(): Boolean
    suspend fun handshake(uid: Int, sessionToken: String): Boolean
    suspend fun preheatClassLoader(packageName: String)
    suspend fun sendPause(): Boolean
    suspend fun sendResume(): Boolean
    suspend fun sendShutdown(): Boolean
    suspend fun startEventLoop(onError: (String) -> Unit)
    suspend fun stopEventLoop()
    /**
     * 在新会话进入 STARTING 之后立即清空上一会话遗留的事件 / 覆盖率统计,
     * 确保切换 App 重新开始监控时 UI 立刻反映"全新会话",避免旧事件残留。
     */
    suspend fun clearPreviousEvents()
    fun isSocketConnected(): Boolean
}
