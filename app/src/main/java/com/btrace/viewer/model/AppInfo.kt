package com.btrace.viewer.model

import android.graphics.drawable.Drawable

/**
 * 应用信息数据模型
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val icon: Drawable?
) {
    /**
     * 格式化的UID显示
     */
    val uidDisplay: String get() = "UID: $uid"
}
