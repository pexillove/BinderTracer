package com.btrace.viewer.parser

import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把发送方进程视角的 binder handle 反查到目标进程名。
 *
 * 数据来源:
 *   - `/dev/binderfs/binder_logs/state` 全局 dump:每行 `node N: ub.. cb.. ... proc P` →
 *     建 nodeId → ownerPid 全局表
 *   - `/dev/binderfs/binder_logs/proc/<senderPid>` 单进程 dump:`context binder` 段下的
 *     `ref X: desc N node Y` 行 → 建 (senderPid, handle=N) → nodeId 表
 *   - `/proc/<pid>/cmdline` 拿目标进程名(替换内嵌 NUL 为空字符)
 *
 * 这样得到 (senderPid, handle) → (ownerPid, processName)。covers ServiceManager 注册的
 * 系统服务(handle 通常稳定)+ 进程通过 bindService / Binder 传递拿到的私有句柄。
 *
 * **限制**:只到进程级别,不到具体 service 名。一个 system_server 进程持有几十个服务
 * (IPackageManager / IActivityManager / ...),命中同一进程时 service 名仍区分不出。
 * 后续若需要可叠加 `dumpsys -l` 反查,但单条 dumpsys 都要 ~100ms,200+ service 太重。
 *
 * **TTL**:30s 内复用上次快照(handle 在监控期间会持续分配新值,需要周期性重建)。
 */
@Singleton
class BinderHandleResolver @Inject constructor() {

    companion object {
        private const val TAG = "BinderHandleResolver"
        private const val TTL_MS = 30_000L
        private const val SHELL_TIMEOUT_MS = 3_000L

        private const val STATE_PATH = "/dev/binderfs/binder_logs/state"
        private fun procPath(pid: Int) = "/dev/binderfs/binder_logs/proc/$pid"

        // ref 行示例:`  ref 1258525: desc 1 node 13547 s 1 w 1 d 0...`
        private val REF_REGEX = Regex("""^\s*ref\s+\d+:\s+desc\s+(\d+)\s+node\s+(\d+)""")
        // node 行示例:`  node 1287795: ub.. cb.. ... proc 2223`
        // 多 owner 共有时尾部可能是 `proc 2223 5278`,取第一个 pid
        private val NODE_REGEX = Regex("""^\s*node\s+(\d+):.*?proc\s+(\d+)""")
    }

    private data class CacheEntry(
        val senderPid: Int,
        val createdAtMs: Long,
        // (senderPid, handle) → (ownerPid, processName)
        val handleToTarget: Map<Int, ResolvedTarget>
    )

    @Volatile
    private var cache: CacheEntry? = null
    private val refreshMu = Mutex()

    // Singleton 内置 IO scope。lookup miss 时 fire-and-forget 一个 refresh,但用
    // [lastRefreshTriggerMs] + 1s 间隔节流,避免一波密集事件全部触发 root shell。
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRefreshTriggerMs = AtomicLong(0L)
    private val refreshTriggerCooldownMs = 1_000L

    /** 反查结果。null 表示当前 handle 在 binderfs 里查不到(可能是 monitor 启动后新分配的、需要 refresh)。 */
    data class ResolvedTarget(val ownerPid: Int, val processName: String)

    /**
     * 同步 lookup。**绝不 IO**,只查内存缓存。命中返回 ResolvedTarget,否则 null。
     *
     * miss 时按 [refreshTriggerCooldownMs] 间隔自触发 refresh(fire-and-forget),
     * 这样 ParcelParser 同步路径无需关心生命周期,首条事件到来即开始建表,
     * 后续命中即可。
     *
     * 调用方:[com.btrace.viewer.parser.decoders.TargetRefDecoder]。
     */
    fun lookup(senderPid: Int, handle: Long): ResolvedTarget? {
        val c = cache
        if (c != null && c.senderPid == senderPid &&
            System.currentTimeMillis() - c.createdAtMs <= TTL_MS
        ) {
            // handle 是发送方进程视角的 desc 编号,32 位足够(实测 desc 不超过几千)
            return c.handleToTarget[handle.toInt()]
        }
        // miss → 节流后触发一次异步 refresh,本次 lookup 仍返回 null
        triggerRefreshIfDue(senderPid)
        return null
    }

    private fun triggerRefreshIfDue(senderPid: Int) {
        val now = System.currentTimeMillis()
        val last = lastRefreshTriggerMs.get()
        if (now - last < refreshTriggerCooldownMs) return
        if (!lastRefreshTriggerMs.compareAndSet(last, now)) return
        internalScope.launch { refresh(senderPid) }
    }

    /**
     * 异步 refresh:读 binderfs + cmdline,重建 (handle → target) 缓存。
     * 已有任务在跑时直接复用,不重复发 root shell。
     */
    fun refreshAsync(scope: CoroutineScope, senderPid: Int) {
        scope.launch(Dispatchers.IO) { refresh(senderPid) }
    }

    suspend fun refresh(senderPid: Int) {
        if (senderPid <= 0) return
        val existing = cache
        if (existing != null && existing.senderPid == senderPid &&
            System.currentTimeMillis() - existing.createdAtMs < TTL_MS / 2
        ) {
            // 半 TTL 内已经有新鲜快照,跳过
            return
        }
        if (!refreshMu.tryLock()) {
            // 已有 goroutine 在跑,直接 return,等它完成
            return
        }
        try {
            val nodeToOwner = readNodeOwnerMap()
            if (nodeToOwner.isEmpty()) {
                CLogUtils.w(TAG, "refresh() state 解析为空,放弃本次 refresh")
                return
            }
            val handleToNode = readHandleToNode(senderPid)
            if (handleToNode.isEmpty()) {
                CLogUtils.w(TAG, "refresh() proc/$senderPid 解析为空,放弃本次 refresh")
                return
            }
            val pidToName = HashMap<Int, String>()
            val resolved = HashMap<Int, ResolvedTarget>(handleToNode.size)
            for ((handle, nodeId) in handleToNode) {
                val ownerPid = nodeToOwner[nodeId] ?: continue
                val name = pidToName.getOrPut(ownerPid) { readCmdline(ownerPid) }
                resolved[handle] = ResolvedTarget(ownerPid = ownerPid, processName = name)
            }
            cache = CacheEntry(
                senderPid = senderPid,
                createdAtMs = System.currentTimeMillis(),
                handleToTarget = resolved
            )
            CLogUtils.i(TAG, "refresh() 完成 senderPid=$senderPid handles=${resolved.size}")
        } finally {
            refreshMu.unlock()
        }
    }

    /** 主动失效缓存 —— 监控停止 / 切换目标 App 时调。 */
    fun invalidate() {
        cache = null
    }

    private suspend fun readNodeOwnerMap(): Map<Int, Int> {
        val (code, out) = execRoot("cat $STATE_PATH 2>/dev/null")
        if (code != 0 || out.isEmpty()) {
            CLogUtils.w(TAG, "readNodeOwnerMap() state 读取失败 code=$code outLen=${out.length}")
            return emptyMap()
        }
        return parseNodeOwnerMap(out)
    }

    internal fun parseNodeOwnerMap(text: String): Map<Int, Int> {
        val m = HashMap<Int, Int>(8192)
        for (line in text.lineSequence()) {
            val match = NODE_REGEX.find(line) ?: continue
            val nodeId = match.groupValues[1].toIntOrNull() ?: continue
            val ownerPid = match.groupValues[2].toIntOrNull() ?: continue
            // 同一 nodeId 在不同段(active / dead nodes)出现,取第一个非空 owner
            m.putIfAbsent(nodeId, ownerPid)
        }
        return m
    }

    private suspend fun readHandleToNode(senderPid: Int): Map<Int, Int> {
        val (code, out) = execRoot("cat ${procPath(senderPid)} 2>/dev/null")
        if (code != 0 || out.isEmpty()) {
            CLogUtils.w(TAG, "readHandleToNode() proc/$senderPid 读取失败 code=$code outLen=${out.length}")
            return emptyMap()
        }
        return parseHandleToNode(out)
    }

    /**
     * 只采 `context binder` 段下的 ref 行。同一 dump 含 hwbinder / vndbinder / binder 三段,
     * App 视角的 handle 都是 binder context 的;混采会让 handle 编号空间撞车。
     */
    internal fun parseHandleToNode(text: String): Map<Int, Int> {
        val m = HashMap<Int, Int>(256)
        var inBinderContext = false
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("context ")) {
                inBinderContext = trimmed == "context binder"
                continue
            }
            if (!inBinderContext) continue
            val match = REF_REGEX.find(line) ?: continue
            val desc = match.groupValues[1].toIntOrNull() ?: continue
            val nodeId = match.groupValues[2].toIntOrNull() ?: continue
            // 同一 desc 出现两次(不应该,但兜底)取第一次
            m.putIfAbsent(desc, nodeId)
        }
        return m
    }

    private suspend fun readCmdline(pid: Int): String {
        val (code, out) = execRoot("cat /proc/$pid/cmdline 2>/dev/null")
        if (code != 0) return "pid=$pid"
        // cmdline 用 NUL 分隔 argv,通常只关心 argv[0]
        val first = out.split(' ').firstOrNull()?.trim().orEmpty()
        return first.ifEmpty { "pid=$pid" }
    }

    /**
     * test-only hook:替换以注入 fake shell。生产默认走 `ProcessBuilder("su","-c",...)`
     * 一次性 root 进程,与 BTraceManager 的 execStandaloneRoot 同样的隔离套路。
     */
    internal var execRoot: suspend (String) -> Pair<Int, String> = ::defaultExecRoot

    private suspend fun defaultExecRoot(command: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    Pair(-1, output)
                } else {
                    Pair(process.exitValue(), output)
                }
            } catch (e: Exception) {
                CLogUtils.w(TAG, "execRoot() 异常: ${e.message}")
                Pair(-1, "")
            }
        }
}
