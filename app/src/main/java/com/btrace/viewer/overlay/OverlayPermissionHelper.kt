package com.btrace.viewer.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * SYSTEM_ALERT_WINDOW 在 API 23+ 默认拒绝,Manifest 声明只是让用户"能在系统设置里授权"。
 * 实际能不能 addView 必须运行时 [canDraw] 通过。
 */
object OverlayPermissionHelper {

    fun canDraw(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun openSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
