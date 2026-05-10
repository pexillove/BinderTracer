package com.btrace.viewer.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btrace.viewer.data.AppRepository
import com.btrace.viewer.model.AppInfo
import com.btrace.viewer.service.MonitoringServiceConnector
import com.btrace.viewer.service.MonitoringSessionState
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用选择界面UI状态
 */
data class AppsUiState(
    val apps: List<AppInfo> = emptyList(),
    val filteredApps: List<AppInfo> = emptyList(),
    val searchQuery: String = "",
    val selectedApp: AppInfo? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val includeSystemApps: Boolean = false,
    val isStartingMonitor: Boolean = false,
    val errorMessage: String? = null,
    val navigateToMonitor: Boolean = false
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val connector: MonitoringServiceConnector,
    private val eventRepository: com.btrace.viewer.data.EventRepository
) : ViewModel() {

    companion object { private const val TAG = "AppsViewModel" }

    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    init {
        CLogUtils.d(TAG, "init() AppsViewModel初始化")
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val apps = appRepository.getInstalledApps(_uiState.value.includeSystemApps)
                _uiState.value = _uiState.value.copy(
                    apps = apps,
                    filteredApps = filterApps(apps, _uiState.value.searchQuery),
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "加载应用列表失败: ${e.message}"
                )
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val apps = appRepository.getInstalledApps(_uiState.value.includeSystemApps)
                _uiState.value = _uiState.value.copy(
                    apps = apps,
                    filteredApps = filterApps(apps, _uiState.value.searchQuery),
                    isRefreshing = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    errorMessage = "刷新失败: ${e.message}"
                )
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredApps = filterApps(_uiState.value.apps, query)
        )
    }

    private fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> {
        if (query.isBlank()) return apps
        val lowerQuery = query.lowercase()
        return apps.filter {
            it.appName.lowercase().contains(lowerQuery) ||
            it.packageName.lowercase().contains(lowerQuery)
        }
    }

    fun onAppSelected(app: AppInfo) {
        val newSelection = if (_uiState.value.selectedApp == app) null else app
        _uiState.value = _uiState.value.copy(selectedApp = newSelection)
    }

    fun toggleSystemApps() {
        _uiState.value = _uiState.value.copy(includeSystemApps = !_uiState.value.includeSystemApps)
        loadApps()
    }

    /**
     * 启动监控:bind 到 [com.btrace.viewer.service.MonitoringService] 并下发启动指令。
     * 等到状态走出 STARTING(到 CONNECTED 或 FAILED)再决定是导航还是报错。
     */
    fun startMonitoring() {
        val selectedApp = _uiState.value.selectedApp ?: return
        if (selectedApp.uid <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "目标应用 uid 不合法,无法启动监控")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isStartingMonitor = true, errorMessage = null)
            try {
                val controller = connector.ensureBound()
                // spec § 6.5:把目标 uid 灌进 EventRepository,parseEvent 推断 Direction
                // 才能区分入向/出向。
                eventRepository.setTargetUid(selectedApp.uid)
                controller.startMonitoring(
                    targetUid = selectedApp.uid,
                    packageName = selectedApp.packageName,
                    appName = selectedApp.appName
                )

                val finalState = controller.state.first {
                    it == MonitoringSessionState.CONNECTED ||
                    it == MonitoringSessionState.FAILED
                }

                if (finalState == MonitoringSessionState.CONNECTED) {
                    _uiState.value = _uiState.value.copy(
                        isStartingMonitor = false,
                        navigateToMonitor = true
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isStartingMonitor = false,
                        errorMessage = controller.errorMessage.first() ?: "监控启动失败"
                    )
                }
            } catch (e: Exception) {
                CLogUtils.e(TAG, "startMonitoring() 异常: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isStartingMonitor = false,
                    errorMessage = "启动监控失败: ${e.message}"
                )
            }
        }
    }

    fun onNavigatedToMonitor() {
        _uiState.value = _uiState.value.copy(navigateToMonitor = false)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
