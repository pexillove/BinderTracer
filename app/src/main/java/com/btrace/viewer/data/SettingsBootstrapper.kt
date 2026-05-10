package com.btrace.viewer.data

import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用启动后把持久化的设置项装载到内存中的 [EventRepository],并持续订阅变更。
 *
 * 之所以单独做一个类:Application 不能 @Inject 字段(只能 EntryPoint),让它注入到 Service /
 * 任意 Singleton 都行,首次构造即触发。这里的 [start] 由 [com.btrace.viewer.BTraceApp.onCreate]
 * 通过 EntryPoint 调用一次。
 */
@Singleton
class SettingsBootstrapper @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val eventRepository: EventRepository
) {
    companion object { private const val TAG = "SettingsBootstrapper" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settingsRepository.maxEventsFlow.distinctUntilChanged().collect { value ->
                CLogUtils.d(TAG, "apply maxEvents=$value -> EventRepository")
                eventRepository.setMaxEvents(value)
            }
        }
    }
}
