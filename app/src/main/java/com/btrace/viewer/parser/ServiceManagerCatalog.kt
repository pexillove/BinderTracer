package com.btrace.viewer.parser

import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统 service 全表反射缓存。
 *
 * **背景**:`TargetRefDecoder` 兜底场景(parcel 头部没 InterfaceToken,只剩 binder ref)
 * 下,普通 [MethodResolver] 路径以 InterfaceToken 作 class FQN 反射 `${descriptor}$Stub`
 * 类,**入口缺失**,无法解析 method name。
 *
 * **方案**(对应"反射获取 method name"):
 *   1. 启动监控时跑 `service list` 拿到 (svcName, descriptor) 全表
 *   2. 对每个 descriptor 反射 `${descriptor}$Stub` 类,通过 [getDefaultTransactionName] /
 *      `TRANSACTION_*` 静态字段建立 (descriptor → Map<code, methodName>)
 *   3. 反向索引 (code → List<MethodCandidate>),`target@0xN` 兜底时按 code +
 *      [toPackage] 反查候选 service / method
 *
 * **生命周期**:Singleton,与 [BinderHandleResolver] 同生命周期。监控按下"开始"时
 * 同步 [warmup],监控停止时调 [invalidate]。
 *
 * **不变量**:本表仅在 `target@0xN` 兜底时使用,**不替代** [MethodResolver] 主路径。
 * 因此 catalog miss / 反射失败 / service list 跑空都不阻塞主流程,自动降级到原 raw
 * `target@0xN` 显示。
 */
@Singleton
class ServiceManagerCatalog @Inject constructor() {

    companion object {
        private const val TAG = "ServiceManagerCatalog"
        private const val SHELL_TIMEOUT_MS = 10_000L
        // service list 行格式:`<index>\t<svcName>: [<descriptor>]`
        private val SERVICE_LIST_LINE = Regex("""^\d+\s+([^:]+):\s+\[([^\]]*)\]\s*$""")

        private const val TRANSACTION_PREFIX = "TRANSACTION_"
    }

    /**
     * `target@0xN` 反查候选条目。一个 code 可能命中多个 service(不同 service 的 method
     * 编号空间不冲突,但 code=N 在多个 descriptor 下都可能有意义);UI 展示首条最优,详情
     * 调试可看完整列表。
     */
    data class MethodCandidate(
        val serviceName: String,    // service list 第二列(注册名,如 "audio")
        val descriptor: String,     // InterfaceToken,如 "android.media.IAudioService"
        val methodName: String,     // 反射出的 method 名
    )

    private data class Snapshot(
        val byCode: Map<Int, List<MethodCandidate>>,
        val totalServices: Int,
        val reflectedServices: Int,
        val totalCodes: Int,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    private val warmupMu = Mutex()

    /**
     * 同步 warmup:跑 `service list` + 反射所有 Stub 类,建立 code → candidates 反向索引。
     * 已 warmup 过的快照存在则直接返回(避免重复跑)。
     *
     * **耗时**:约 1-3s,取决于 service 数。340 个 service 单条反射 < 5ms,大头在
     * shell 的 `service list` 阻塞读 (~500ms 真机)。整个 warmup 在 IO 线程。
     */
    suspend fun warmup() = warmupMu.withLock {
        if (snapshot != null) return@withLock
        snapshot = withContext(Dispatchers.IO) { buildSnapshot() }
    }

    /**
     * `target@0xN` 反查:按 code 找候选 method。
     *   - 命中且 toPackage 不为空时,用 toPackage 做次序优先(候选里有 service name 包含
     *     toPackage 子串的优先)
     *   - 候选只剩多条且无法用 toPackage 缩窄时,**返回首条**(随时间 service list 顺序),
     *     UI 端可标 `?` 提示候选有多条
     *   - miss 返回 null,调用方降级到原 `target@0xN` 显示
     */
    fun lookupByCode(code: Int, toPackage: String?): MethodCandidate? {
        val s = snapshot ?: return null
        val candidates = s.byCode[code] ?: return null
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        if (!toPackage.isNullOrEmpty()) {
            // toPackage 是 com.miui.audiomonitor 之类,service name 是 "audiomonitor"。
            // 简单 substring 匹配:取末段(最后一个 . 后)与 service name 互相包含。
            val pkgTail = toPackage.substringAfterLast('.').lowercase()
            candidates.firstOrNull { c ->
                val name = c.serviceName.lowercase()
                pkgTail.contains(name) || name.contains(pkgTail)
            }?.let { return it }
        }
        return candidates[0]
    }

    /** 监控停止时清缓存,避免下次启动时拿到过期数据(service list 在系统重启 / 应用切换后变化)。 */
    fun invalidate() {
        snapshot = null
    }

    /** 仅供单测/统计使用。 */
    fun stats(): Triple<Int, Int, Int>? = snapshot?.let {
        Triple(it.totalServices, it.reflectedServices, it.totalCodes)
    }

    // ────────── internal ──────────

    private suspend fun buildSnapshot(): Snapshot {
        val rawList = runShell("service list")
        if (rawList.isEmpty()) {
            CLogUtils.w(TAG, "buildSnapshot() service list 输出为空,跳过 warmup")
            return Snapshot(emptyMap(), 0, 0, 0)
        }
        val entries = parseServiceList(rawList)
        val byCode = HashMap<Int, MutableList<MethodCandidate>>(entries.size * 4)
        var reflected = 0
        for ((svcName, descriptor) in entries) {
            if (descriptor.isEmpty()) continue
            val codeMap = reflectStubTransactions(descriptor) ?: continue
            reflected++
            for ((code, methodName) in codeMap) {
                byCode.getOrPut(code) { ArrayList(2) }
                    .add(MethodCandidate(svcName, descriptor, methodName))
            }
        }
        val totalCodes = byCode.values.sumOf { it.size }
        CLogUtils.i(
            TAG,
            "buildSnapshot() 完成 services=${entries.size} reflected=$reflected " +
                "codes=$totalCodes"
        )
        return Snapshot(byCode, entries.size, reflected, totalCodes)
    }

    internal fun parseServiceList(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(384)
        for (line in text.lineSequence()) {
            val match = SERVICE_LIST_LINE.find(line) ?: continue
            val svcName = match.groupValues[1].trim()
            val descriptor = match.groupValues[2].trim()
            if (svcName.isEmpty()) continue
            out.add(svcName to descriptor)
        }
        return out
    }

    /**
     * 反射 `${descriptor}$Stub` 类,枚举 `TRANSACTION_xxx = N` 静态 int 字段,
     * 优先调 `getDefaultTransactionName(int)` 拿到正式 method name(API 28+ 才有);
     * 不可用时降级用 field 名末段(`TRANSACTION_acquireWakeLock` → `acquireWakeLock`)。
     *
     * 反射失败(class not found / hidden api 拦截 / 非 AIDL Stub)时返回 null,catalog
     * 跳过该 descriptor。
     */
    private fun reflectStubTransactions(descriptor: String): Map<Int, String>? {
        val stubClass = try {
            Class.forName("$descriptor\$Stub")
        } catch (e: Throwable) {
            return null
        }
        // 优先尝试静态方法 getDefaultTransactionName(int)
        val getDefaultName = try {
            stubClass.getDeclaredMethod("getDefaultTransactionName", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        } catch (e: Throwable) {
            null
        }
        val codeMap = HashMap<Int, String>(16)
        for (field in stubClass.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            if (!Modifier.isFinal(field.modifiers)) continue
            if (field.type != Int::class.javaPrimitiveType) continue
            if (!field.name.startsWith(TRANSACTION_PREFIX)) continue
            val code = try {
                field.isAccessible = true
                field.getInt(null)
            } catch (e: Throwable) {
                continue
            }
            val methodName = if (getDefaultName != null) {
                try {
                    getDefaultName.invoke(null, code) as? String
                } catch (e: Throwable) {
                    null
                }
            } else null
            // 兜底:从 field 名 TRANSACTION_xxx 截尾
            codeMap[code] = methodName?.takeIf { it.isNotEmpty() }
                ?: field.name.removePrefix(TRANSACTION_PREFIX)
        }
        return if (codeMap.isEmpty()) null else codeMap
    }

    /**
     * test-only hook:替换以注入 fake shell。生产默认走 `ProcessBuilder("su","-c",...)`。
     */
    internal var execShell: suspend (String) -> String = ::defaultExecShell

    private suspend fun runShell(command: String): String = execShell(command)

    private suspend fun defaultExecShell(command: String): String =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    ""
                } else if (process.exitValue() != 0) {
                    CLogUtils.w(TAG, "runShell($command) 退出码=${process.exitValue()}")
                    ""
                } else {
                    output
                }
            } catch (e: Exception) {
                CLogUtils.w(TAG, "runShell($command) 异常: ${e.message}")
                ""
            }
        }
}
