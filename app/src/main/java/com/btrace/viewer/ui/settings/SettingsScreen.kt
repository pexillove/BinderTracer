package com.btrace.viewer.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 显示Toast消息
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToastMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态检查部分(Root + Socket;btrace 进程检查通过 socket 间接体现,不再单列)
            StatusCheckSection(
                statusCheck = uiState.statusCheck,
                onCheckRoot = { viewModel.checkRootStatus() },
                onCheckSocket = { viewModel.checkSocketStatus() },
                onRefreshAll = { viewModel.checkAllStatus() }
            )

            // 事件缓存上限设置
            EventBufferLimitSection(
                currentMax = viewModel.maxEvents.collectAsState().value,
                range = viewModel.maxEventsRange,
                onCommit = { viewModel.setMaxEvents(it) }
            )

            // 监控悬浮窗开关
            OverlaySection(
                enabled = viewModel.overlayEnabled.collectAsState().value,
                canDraw = viewModel.canDrawOverlays(context),
                onToggle = { viewModel.setOverlayEnabled(it, context) }
            )

            // 运行环境诊断
            DiagnosticsSection(
                running = uiState.diagnosticsRunning,
                result = uiState.diagnosticsResult,
                onRun = { viewModel.runDiagnostics() }
            )

            // 关于部分
            AboutSection(
                appVersion = uiState.appVersion
            )
        }
    }
}

@Composable
private fun OverlaySection(
    enabled: Boolean,
    canDraw: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "监控悬浮窗",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "监控时屏幕角落显示一个气泡,实时看速率;单击展开看最近事件。" +
                               "需要『在其他应用上层显示』权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled && canDraw,
                    onCheckedChange = onToggle
                )
            }
            if (enabled && !canDraw) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已开启但缺少权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onToggle(true) }) {
                        Text("去授权")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(
    running: Boolean,
    result: com.btrace.viewer.utils.EnvironmentCheckResult?,
    onRun: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "运行环境诊断",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = onRun, enabled = !running) {
                    Text(if (running) "运行中…" else "运行诊断")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "检查 root、libsu、全局挂载命名空间等是否就绪。" +
                       "App 启动时不再自动执行,需要时手动点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (result != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.overallSuccess) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                result.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (item.success) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (item.success) Color(0xFF4CAF50)
                                   else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                item.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventBufferLimitSection(
    currentMax: Int,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit
) {
    // 拖动时只更新本地 state,松手才写回 DataStore,避免每帧 IO。
    var localValue by remember(currentMax) { mutableFloatStateOf(currentMax.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "事件缓存",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最大保留事件数",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = localValue.toInt().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = localValue,
                onValueChange = { localValue = it },
                onValueChangeFinished = { onCommit(localValue.toInt()) },
                valueRange = range,
                steps = 0
            )
            Text(
                text = "范围 ${range.start.toInt()}–${range.endInclusive.toInt()}。" +
                       "调小会立即裁剪掉最老的事件;调大不影响已缓存内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCheckSection(
    statusCheck: StatusCheckState,
    onCheckRoot: () -> Unit,
    onCheckSocket: () -> Unit,
    onRefreshAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "状态检查",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRefreshAll) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新全部"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Root权限状态
            StatusRow(
                label = "Root权限",
                status = statusCheck.isRootAvailable,
                isChecking = statusCheck.isChecking,
                onCheck = onCheckRoot
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Socket连接状态(daemon 还活着的间接证据)
            StatusRow(
                label = "TCP连接",
                status = statusCheck.isSocketConnected,
                isChecking = statusCheck.isChecking,
                onCheck = onCheckSocket
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    status: Boolean?,
    isChecking: Boolean,
    onCheck: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            StatusIndicator(status = status, isChecking = isChecking)
        }

        OutlinedButton(
            onClick = onCheck,
            modifier = Modifier.height(36.dp)
        ) {
            Text("检查", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusIndicator(
    status: Boolean?,
    isChecking: Boolean
) {
    when {
        isChecking -> {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        }

        status == null -> {
            Text(
                text = "[未检查]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        status -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[已获取]",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "成功",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[未获取]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "失败",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AboutSection(
    appVersion: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 版本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "版本",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = appVersion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 检查更新按钮
            Button(
                onClick = { /* TODO: 实现检查更新功能 */ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("检查更新")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 开源地址
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "开源地址",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "github.com/Anekys/BinderTracer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 项目说明
            Text(
                text = "BTrace Viewer 是一个用于可视化 Android Binder 通信的工具。通过 eBPF 技术捕获系统 Binder 调用事件，并以直观的方式展示给用户。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
