package com.btrace.viewer.utils

import android.util.Log

/**
 * 日志工具类
 * 支持超长日志自动分段输出
 */
object CLogUtils {

    private const val TAG = "BTrace"

    private const val LOG_MAX_LENGTH = 2000

    @JvmStatic
    fun v(msg: String) {
        logSplit(Log.VERBOSE, TAG, msg, null)
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        logSplit(Log.VERBOSE, tag, msg, null)
    }

    @JvmStatic
    fun v(msg: String, throwable: Throwable) {
        logSplit(Log.VERBOSE, TAG, msg, throwable)
    }

    @JvmStatic
    fun v(tag: String, msg: String, throwable: Throwable) {
        logSplit(Log.VERBOSE, tag, msg, throwable)
    }

    @JvmStatic
    fun d(msg: String) {
        logSplit(Log.DEBUG, TAG, msg, null)
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        logSplit(Log.DEBUG, tag, msg, null)
    }

    @JvmStatic
    fun d(msg: String, throwable: Throwable) {
        logSplit(Log.DEBUG, TAG, msg, throwable)
    }

    @JvmStatic
    fun d(tag: String, msg: String, throwable: Throwable) {
        logSplit(Log.DEBUG, tag, msg, throwable)
    }

    @JvmStatic
    fun i(msg: String) {
        logSplit(Log.INFO, TAG, msg, null)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        logSplit(Log.INFO, tag, msg, null)
    }

    @JvmStatic
    fun i(msg: String, throwable: Throwable) {
        logSplit(Log.INFO, TAG, msg, throwable)
    }

    @JvmStatic
    fun i(tag: String, msg: String, throwable: Throwable) {
        logSplit(Log.INFO, tag, msg, throwable)
    }

    @JvmStatic
    fun w(msg: String) {
        logSplit(Log.WARN, TAG, msg, null)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        logSplit(Log.WARN, tag, msg, null)
    }

    @JvmStatic
    fun w(msg: String, throwable: Throwable) {
        logSplit(Log.WARN, TAG, msg, throwable)
    }

    @JvmStatic
    fun w(tag: String, msg: String, throwable: Throwable) {
        logSplit(Log.WARN, tag, msg, throwable)
    }

    @JvmStatic
    fun e(msg: String) {
        logSplit(Log.ERROR, TAG, msg, null)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        logSplit(Log.ERROR, tag, msg, null)
    }

    @JvmStatic
    fun e(msg: String, throwable: Throwable) {
        logSplit(Log.ERROR, TAG, msg, throwable)
    }

    @JvmStatic
    fun e(tag: String, msg: String, throwable: Throwable) {
        logSplit(Log.ERROR, tag, msg, throwable)
    }

    /**
     * 格式化字节数组为十六进制字符串（用于日志输出）
     */
    @JvmStatic
    fun formatBytes(bytes: ByteArray, maxBytes: Int = 64): String {
        if (bytes.isEmpty()) return "[]"
        val displayBytes = if (bytes.size <= maxBytes) bytes else bytes.copyOfRange(0, maxBytes)
        val hex = displayBytes.joinToString(" ") { String.format("%02X", it) }
        return if (bytes.size > maxBytes) {
            "[$hex ... (${bytes.size} bytes total)]"
        } else {
            "[$hex]"
        }
    }

    /**
     * 分段输出日志（解决Android日志长度限制问题）
     */
    private fun logSplit(priority: Int, tag: String?, msg: String?, throwable: Throwable?) {
        val safeTag = tag ?: TAG
        val safeMsg = msg ?: "null"
        val strLength = safeMsg.length
        
        if (strLength == 0) {
            logOnce(priority, safeTag, safeMsg, throwable)
            return
        }
        
        var start = 0
        while (start < strLength) {
            val end = minOf(strLength, start + LOG_MAX_LENGTH)
            val str = safeMsg.substring(start, end)
            // 只在最后一段附加 throwable
            val chunkThrowable = if (end >= strLength) throwable else null
            logOnce(priority, safeTag, str, chunkThrowable)
            start = end
        }
    }

    private fun logOnce(priority: Int, tag: String, msg: String, throwable: Throwable?) {
        when (priority) {
            Log.VERBOSE -> {
                if (throwable != null) Log.v(tag, msg, throwable)
                else Log.v(tag, msg)
            }
            Log.DEBUG -> {
                if (throwable != null) Log.d(tag, msg, throwable)
                else Log.d(tag, msg)
            }
            Log.INFO -> {
                if (throwable != null) Log.i(tag, msg, throwable)
                else Log.i(tag, msg)
            }
            Log.WARN -> {
                if (throwable != null) Log.w(tag, msg, throwable)
                else Log.w(tag, msg)
            }
            Log.ERROR -> {
                if (throwable != null) Log.e(tag, msg, throwable)
                else Log.e(tag, msg)
            }
            Log.ASSERT -> {
                if (throwable != null) Log.wtf(tag, msg, throwable)
                else Log.wtf(tag, msg)
            }
            else -> {
                if (throwable != null) {
                    Log.println(priority, tag, "$msg\n${Log.getStackTraceString(throwable)}")
                } else {
                    Log.println(priority, tag, msg)
                }
            }
        }
    }
}
