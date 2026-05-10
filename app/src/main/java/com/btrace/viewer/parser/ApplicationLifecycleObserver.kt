package com.btrace.viewer.parser

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.launch

/**
 * spec 2026-05-03 § 4.2 Crash-loss 边界:App 进入后台时主动 [PersistentSignatureCache.flushNow]
 * 把 100 ms debounce 窗口内的待写入条目落盘。
 *
 * 注册时机 = [com.btrace.viewer.BTraceApp.onCreate] —— 全局 ProcessLifecycleOwner 上挂,
 * 监听整个 App 进程的 onStop(等价于"所有 Activity 都不再可见")。
 *
 * 注:lifecycle-process 已经是项目依赖(MonitoringService 也用它),无需额外引入。
 */
class ApplicationLifecycleObserver(
    private val persistentCache: PersistentSignatureCache,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppLifecycle"
    }

    /**
     * 由 [com.btrace.viewer.BTraceApp.onCreate] 调用。必须在主线程 —— 内部访问
     * ProcessLifecycleOwner.lifecycle。
     */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        CLogUtils.i(TAG, "ApplicationLifecycleObserver 已挂载到 ProcessLifecycleOwner")
    }

    override fun onStop(owner: LifecycleOwner) {
        // onStop = 所有 Activity 都进入了 stopped 态 = App 进入后台。
        // lifecycleScope.launch 是异步的,不会阻塞 onStop 回调主线程。
        owner.lifecycleScope.launch {
            try {
                persistentCache.flushNow()
                CLogUtils.d(TAG, "onStop() flushNow 完成")
            } catch (t: Throwable) {
                CLogUtils.w(TAG, "onStop() flushNow 失败: ${t.message}", t)
            }
        }
    }
}
