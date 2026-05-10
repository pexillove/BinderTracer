package com.btrace.viewer.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringSessionControllerTest {

    @Test
    fun `initial state is IDLE`() = runTest {
        val controller = MonitoringSessionController(
            transport = FakeTransport(),
            backgroundDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        assertEquals(MonitoringSessionState.IDLE, controller.state.first())
    }

    @Test
    fun `successful start transitions IDLE STARTING HANDSHAKING CONNECTED`() = runTest {
        val transport = FakeTransport(
            startServerResult = true,
            startDaemonResult = true,
            awaitClientResult = true,
            handshakeResult = true
        )
        val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)
        val controller = MonitoringSessionController(transport, dispatcher)

        val seen = mutableListOf<MonitoringSessionState>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { seen.add(it) }
        }

        controller.startMonitoring(targetUid = 10086, packageName = "test", appName = "Test").join()

        collectJob.cancel()
        assertEquals(
            listOf(
                MonitoringSessionState.IDLE,
                MonitoringSessionState.STARTING,
                MonitoringSessionState.HANDSHAKING,
                MonitoringSessionState.CONNECTED
            ),
            seen
        )
    }

    @Test
    fun `start fails at server then state goes FAILED`() = runTest {
        val transport = FakeTransport(startServerResult = false)
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        assertEquals(MonitoringSessionState.FAILED, controller.state.first())
    }

    @Test
    fun `pause and resume only move CONNECTED to PAUSED to CONNECTED`() = runTest {
        val transport = FakeTransport(
            startServerResult = true, startDaemonResult = true,
            awaitClientResult = true, handshakeResult = true,
            pauseResult = true, resumeResult = true
        )
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        assertEquals(MonitoringSessionState.CONNECTED, controller.state.first())

        controller.pause().join()
        assertEquals(MonitoringSessionState.PAUSED, controller.state.first())

        controller.resume().join()
        assertEquals(MonitoringSessionState.CONNECTED, controller.state.first())
    }

    @Test
    fun `stop from any state ends in IDLE`() = runTest {
        val transport = FakeTransport(
            startServerResult = true, startDaemonResult = true,
            awaitClientResult = true, handshakeResult = true,
            shutdownResult = true
        )
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        controller.stop().join()
        assertEquals(MonitoringSessionState.IDLE, controller.state.first())
    }

    @Test
    fun `reset clears FAILED back to IDLE`() = runTest {
        val transport = FakeTransport(startServerResult = false)
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        assertEquals(MonitoringSessionState.FAILED, controller.state.first())
        controller.reset()
        assertEquals(MonitoringSessionState.IDLE, controller.state.first())
    }

    @Test
    fun `enterReconnecting from CONNECTED moves to RECONNECTING`() = runTest {
        val transport = FakeTransport(
            startServerResult = true, startDaemonResult = true,
            awaitClientResult = true, handshakeResult = true
        )
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        controller.enterReconnecting()
        assertEquals(MonitoringSessionState.RECONNECTING, controller.state.first())
    }

    @Test
    fun `attemptReconnect succeeds on first try goes back to CONNECTED`() = runTest {
        val transport = FakeTransport(
            startServerResult = true, startDaemonResult = true,
            awaitClientResult = true, handshakeResult = true
        )
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        controller.enterReconnecting()

        controller.attemptReconnect(maxAttempts = 1, intervalMs = 0).join()

        assertEquals(MonitoringSessionState.CONNECTED, controller.state.first())
    }

    @Test
    fun `startMonitoring clears previous session events exactly once`() = runTest {
        val transport = FakeTransport(
            startServerResult = true, startDaemonResult = true,
            awaitClientResult = true, handshakeResult = true
        )
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        assertEquals(1, transport.clearPreviousEventsCalls)
    }

    @Test
    fun `attemptReconnect exhausts retries and goes FAILED`() = runTest {
        val transport = FakeTransport(
            startServerResult = true, startDaemonResult = true,
            awaitClientResult = true, handshakeResult = true
        )
        val controller = MonitoringSessionController(transport, UnconfinedTestDispatcher(testScheduler))
        controller.startMonitoring(10086, "test", "Test").join()
        controller.enterReconnecting()

        // 切到失败模式 — awaitClient 一直返回 false,attemptReconnect 跑满后失败
        transport.awaitClientResult = false
        controller.attemptReconnect(maxAttempts = 2, intervalMs = 0).join()

        assertEquals(MonitoringSessionState.FAILED, controller.state.first())
    }
}

/**
 * 测试用 Transport,把所有外部副作用替换为参数控制的固定返回。
 * 实际的 [SessionTransport] 在 controller 实现里定义为 interface。
 *
 * Note (controller): the props are var so a follow-up task (5.1) can flip them mid-test;
 * task 4.1 only ever uses the constructor-supplied values.
 *
 * Each suspend method calls yield() so that StateFlow collectors get a chance to observe
 * intermediate states between transitions (required for the transitions-sequence test).
 */
private class FakeTransport(
    var startServerResult: Boolean = true,
    var startDaemonResult: Boolean = true,
    var awaitClientResult: Boolean = true,
    var handshakeResult: Boolean = true,
    var pauseResult: Boolean = true,
    var resumeResult: Boolean = true,
    var shutdownResult: Boolean = true
) : SessionTransport {
    override suspend fun stopExistingDaemon() { yield() }
    override suspend fun disconnectSocket() { yield() }
    override suspend fun startServer(): Boolean { yield(); return startServerResult }
    override suspend fun startDaemon(uid: Int, sessionToken: String): Boolean { yield(); return startDaemonResult }
    override suspend fun awaitClient(): Boolean { yield(); return awaitClientResult }
    override suspend fun handshake(uid: Int, sessionToken: String): Boolean { yield(); return handshakeResult }
    override suspend fun preheatClassLoader(packageName: String) { yield() }
    override suspend fun sendPause(): Boolean { yield(); return pauseResult }
    override suspend fun sendResume(): Boolean { yield(); return resumeResult }
    override suspend fun sendShutdown(): Boolean { yield(); return shutdownResult }
    override suspend fun startEventLoop(onError: (String) -> Unit) { yield() }
    override suspend fun stopEventLoop() { yield() }
    var clearPreviousEventsCalls: Int = 0
    override suspend fun clearPreviousEvents() { yield(); clearPreviousEventsCalls++ }
    override fun isSocketConnected(): Boolean = true
}
