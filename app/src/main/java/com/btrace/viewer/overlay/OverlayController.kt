package com.btrace.viewer.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class OverlayUiState(
    val targetAppName: String = "",
    val eventCount: Int = 0,
    val eventRate: Int = 0,
    val recentEvents: List<BinderEvent> = emptyList()
)

/**
 * 监控悬浮窗。单击气泡切换折叠/展开;按住拖动可移动位置。
 *
 * 折叠态:48dp 圆形,中心显示 eventRate 数字 + 颜色环(红/橙/蓝随速率分级)。
 * 展开态:280×~340dp 卡片,顶部目标 App 名 + 折叠/关闭按钮,中部最近 10 条事件
 *        (借鉴 ProxyPin:9-10sp 字号 + Divider 0.3dp + 倒序 + 单行省略),底部速率 + 总数。
 *
 * 触摸冲突处理:onTouch ACTION_DOWN 始终 return false 让 Compose clickable 收到 ACTION_UP →
 * 触发单击;ACTION_MOVE 当移动 > 阈值时才消费,update WindowManager 位置。这样静态单击和
 * 拖动都能工作。
 */
@Singleton
class OverlayController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OverlayController"
        private const val DRAG_THRESHOLD_PX = 10
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var hostView: OverlayHostView? = null
    private var lastParams: WindowManager.LayoutParams? = null

    // 内部 UI state(content provider 通过 update() 推);Compose 通过 collectAsState 读
    private val uiState = MutableStateFlow(OverlayUiState())
    private val expanded = MutableStateFlow(false)

    fun isShowing(): Boolean = hostView != null

    /**
     * 由 caller(MonitoringService 的 collect 协程)推最新 UI 状态。Compose 自动 recompose。
     */
    fun update(state: OverlayUiState) {
        uiState.value = state
    }

    /**
     * 在屏幕上显示悬浮窗。返回 false 表示无 SYSTEM_ALERT_WINDOW 权限。
     * 重复 show 是 no-op。
     */
    fun show(): Boolean {
        if (hostView != null) return true
        if (!OverlayPermissionHelper.canDraw(context)) {
            CLogUtils.w(TAG, "show() 拒绝:无 SYSTEM_ALERT_WINDOW 权限")
            return false
        }

        val host = OverlayHostView(context) {
            val state by uiState.collectAsState()
            val isExpanded by expanded.collectAsState()
            if (isExpanded) {
                OverlayExpanded(
                    state = state,
                    onCollapse = { expanded.value = false },
                    onMaximize = { launchMainApp() },
                    onClose = { hide() }
                )
            } else {
                OverlayBubble(
                    rate = state.eventRate,
                    onClick = { expanded.value = true }
                )
            }
        }
        hostView = host

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }
        lastParams = params

        host.view.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var downRawX = 0f
            var downRawY = 0f
            var dragging = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        downRawX = e.rawX
                        downRawY = e.rawY
                        dragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        if (!dragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(v, params)
                            } catch (t: Throwable) {
                                CLogUtils.w(TAG, "updateViewLayout 异常: ${t.message}")
                            }
                            return true
                        }
                        return false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        return dragging  // 拖动后吞掉 UP,防止 click 触发
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(host.view, params)
            CLogUtils.i(TAG, "show() 悬浮窗已添加")
        } catch (t: Throwable) {
            CLogUtils.e(TAG, "addView 失败: ${t.message}", t)
            host.dispose()
            hostView = null
            return false
        }
        return true
    }

    /**
     * 把主 app 拉到前台。Service / overlay 回调里启动 Activity 必须 FLAG_ACTIVITY_NEW_TASK。
     * 用 packageManager.getLaunchIntentForPackage 避免硬编码 MainActivity 类名。
     * 启动后 ProcessLifecycleOwner ON_START 触发,MonitoringService 自动 hide overlay,
     * 因此这里不手动 collapse —— 让自动隐藏路径接管,保证回到 app 时悬浮窗一定不挡视线。
     */
    private fun launchMainApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch == null) {
            CLogUtils.w(TAG, "launchMainApp: getLaunchIntentForPackage 返回 null")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(launch)
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "launchMainApp: startActivity 失败: ${t.message}")
        }
    }

    fun hide() {
        val host = hostView ?: return
        try {
            windowManager.removeView(host.view)
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "removeView 异常: ${t.message}")
        }
        host.dispose()
        hostView = null
        expanded.value = false
        CLogUtils.i(TAG, "hide() 悬浮窗已移除")
    }
}

@Composable
private fun OverlayBubble(rate: Int, onClick: () -> Unit) {
    val ringColor = when {
        rate >= 100 -> Color(0xFFE53935)
        rate >= 10 -> Color(0xFFFF9800)
        else -> Color(0xFF42A5F5)
    }
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .border(2.dp, ringColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (rate > 999) "999+" else "$rate",
                color = Color.White,
                fontSize = if (rate > 99) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text("/s", color = Color(0xCCFFFFFF), fontSize = 8.sp)
        }
    }
}

@Composable
private fun OverlayExpanded(
    state: OverlayUiState,
    onCollapse: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        Modifier
            .width(280.dp)
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6000000))
    ) {
        Column(Modifier.fillMaxWidth()) {
            // header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.targetAppName.isEmpty()) "BinderTracer" else state.targetAppName,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "打开 BinderTracer",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onMaximize() }
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "折叠",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onCollapse() }
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onClose() }
                )
            }
            Divider(color = Color(0x33FFFFFF), thickness = 0.5.dp)
            // 最近 10 条事件,倒序(新在上,ProxyPin 风格紧凑列表)
            val tail = state.recentEvents.takeLast(10).asReversed()
            if (tail.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "等待 Binder 事件…",
                        color = Color(0x99FFFFFF),
                        fontSize = 9.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 280.dp)
                ) {
                    items(tail, key = { it.id }) { e ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "${e.callee}.${e.methodName}",
                                color = Color.White,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                e.callerPackage,
                                color = Color(0x99FFFFFF),
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Divider(color = Color(0x22FFFFFF), thickness = 0.3.dp)
                    }
                }
            }
            // footer 速率 + 总数
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "事件 ${state.eventCount}",
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp
                )
                Text(
                    "${state.eventRate}/s",
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
