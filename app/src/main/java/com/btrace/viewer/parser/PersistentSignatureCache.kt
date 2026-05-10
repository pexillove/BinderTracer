package com.btrace.viewer.parser

import android.content.Context
import android.content.SharedPreferences
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * spec 2026-05-03 § 4 持久化签名缓存(`signature_cache_v1`)。
 *
 * **数据 schema**(顶层 JSON,SharedPreferences 单 key 存):
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "packages": {
 *     "<packageName>": {
 *       "version": <versionCode_int>,
 *       "lastUpdated": <lastUpdateTime_long_ms>,
 *       "interfaces": {
 *         "<interfaceName>": {
 *           "<methodName>": {
 *             "code": <int>,
 *             "paramTypes": ["..."],
 *             "returnType": "..."
 *           }, ...
 *         }, ...
 *       }
 *     }, ...
 *   }
 * }
 * ```
 *
 * **schemaVersion 校验**:< 1(缺失或更老)/ > 1(未来版本回退)整体丢弃,不尝试迁移
 * (本 spec 是 v1 首次落地)。
 *
 * **并发模型 = actor channel 单写者**(spec § 4.2):
 *   - `WriteOp.Put`:`(pkg, iface)` 入 hotCopy,pkg 入 pendingDirty
 *   - `WriteOp.Invalidate`:整包从 hotCopy 删,pkg 入 pendingDirty
 *   - `WriteOp.LoadComplete`:磁盘 staging merge 进 hotCopy,粒度 = (pkg, iface),
 *     已 Put 的 (pkg, iface) **绝不**被覆盖;包级元数据 hasPackageMeta 为 false 时才补
 *   - `WriteOp.Flush(ack)`:flush pendingDirty 到磁盘并 ack,supports `flushNow()`
 *
 * Debounce:100 ms 或 pendingDirty 累积 ≥ 16 包触发一次 commit。
 *
 * 容量软上限([MAX_PACKAGES] / [MAX_BYTES_PER_PACKAGE] / [TOTAL_SOFT_LIMIT_BYTES]):
 * commit 时若超限按 lastAccessTime 淘汰最老 packageName 整条;同包字节数超限先按
 * lastAccess 截断最老接口,再决定是否 evict 整包。淘汰 / 截断都打 11 字段 telemetry。
 */
@Singleton
class PersistentSignatureCache(
    @ApplicationContext private val context: Context,
    /** 注入虚拟 dispatcher 让单测可控制 actor 派发与 debounce 时序。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 容量参数:默认值由 BuildConfig 提供(spec § 4.4)。生产路径通过
     * [com.btrace.viewer.di.AppModule.providePersistentSignatureCache]
     * 注入 BuildConfig.SIG_CACHE_* 字段,**不再**依赖伴生对象常量;伴生对象常量
     * 仍保留作为单测/测试构造的 fallback 默认值。
     */
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxPackages: Int = MAX_PACKAGES,
    private val maxBytesPerPackage: Int = MAX_BYTES_PER_PACKAGE,
    private val totalSoftLimitBytes: Long = TOTAL_SOFT_LIMIT_BYTES,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val debounceMaxBatch: Int = DEBOUNCE_MAX_BATCH,
    /** 时钟注入便于单测断言"超过 100ms"边界。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        private const val TAG = "PersistentSignatureCache"
        private const val PREFS_NAME = "btrace_signature_cache"
        internal const val KEY = "signature_cache_v1"
        internal const val CURRENT_SCHEMA_VERSION = 1

        /** spec § 4.4 内存层签名条目 LRU 上限默认值(64..1024)。 */
        const val MAX_ENTRIES: Int = 256
        /** spec § 4.4 磁盘层包数 LRU 上限默认值(16..256)。 */
        const val MAX_PACKAGES: Int = 64
        /** spec § 4.4 单包字节数软上限默认值(1KB..16KB)。 */
        const val MAX_BYTES_PER_PACKAGE: Int = 4 * 1024
        /** spec § 4.4 总磁盘字节数软上限默认值(64KB..1MB)。 */
        const val TOTAL_SOFT_LIMIT_BYTES: Long = 256L * 1024L
        /** spec § 4.2 debounce 时间窗(ms)。 */
        const val DEBOUNCE_MS: Long = 100L
        /** spec § 4.2 debounce 触发阈值(包数)。 */
        const val DEBOUNCE_MAX_BATCH: Int = 16
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 单元数据条目。
     */
    data class PackageData(
        val version: Int,
        val lastUpdated: Long,
        val interfaces: Map<String, Map<String, MethodSignature>>,
    )

    /**
     * Actor 写操作。
     */
    sealed class WriteOp {
        /**
         * spec § 4.2:Put 必须同时携带 versionCode + lastUpdateTime,与 § 4.1 schema 对齐。
         * 元数据来源约定 = `AppClassLoaderRegistry.getPackageMeta()`。
         */
        data class Put(
            val packageName: String,
            val interfaceName: String,
            val versionCode: Int,
            val lastUpdateTime: Long,
            val sig: Map<String, MethodSignature>,
        ) : WriteOp()

        /** 主动失效整包(APK 升级 / 用户清缓存)。 */
        data class Invalidate(val packageName: String) : WriteOp()

        /** 磁盘 → 内存的单向 op,merge 粒度 = (pkg, iface)。 */
        data class LoadComplete(val staging: Map<String, PackageData>) : WriteOp()

        /** flushNow 用,actor 处理到此 op 时立即 commit pendingDirty 并 ack。 */
        data class Flush(val ack: CompletableDeferred<Unit>) : WriteOp()
    }

    /**
     * 内存 hot copy(只在 actor 协程内修改;读路径无锁拷贝快照)。
     *
     * - `interfaces[pkg][iface]` 缺失 = 未命中,需要回退反射
     * - `meta[pkg]` 缺失 = 元数据未知(load 未到 / 未 Put 过)
     */
    private class HotCopy {
        val interfaces = ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, MethodSignature>>>()
        val meta = ConcurrentHashMap<String, AppClassLoaderRegistry.PackageMeta>()
        val lastAccess = ConcurrentHashMap<String, Long>()

        fun put(
            pkg: String, iface: String,
            sig: Map<String, MethodSignature>, now: Long,
        ) {
            val m = interfaces.getOrPut(pkg) { ConcurrentHashMap() }
            m[iface] = sig
            lastAccess[pkg] = now
        }

        fun contains(pkg: String, iface: String): Boolean {
            val m = interfaces[pkg] ?: return false
            return m.containsKey(iface)
        }

        fun putPackageMeta(pkg: String, ver: Int, mtime: Long) {
            meta[pkg] = AppClassLoaderRegistry.PackageMeta(ver, mtime)
        }

        fun hasPackageMeta(pkg: String): Boolean = meta.containsKey(pkg)

        fun removePackage(pkg: String) {
            interfaces.remove(pkg)
            meta.remove(pkg)
            lastAccess.remove(pkg)
        }

        fun get(pkg: String, iface: String): Map<String, MethodSignature>? {
            return interfaces[pkg]?.get(iface)
        }
    }

    private val hot = HotCopy()

    private val writeChannel = Channel<WriteOp>(capacity = Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var writerJob: Job? = null

    @Volatile
    private var loadCompleted: Boolean = false

    init {
        startActor()
    }

    /** 单元测试用:重新启动 actor + 触发 load。生产路径仅 init 调用一次。 */
    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
    internal fun startActor() {
        if (writerJob?.isActive == true) return
        writerJob = scope.launch {
            // 异步从磁盘读 staging,完成后送一个 LoadComplete op。
            val staging = parseJsonFromDisk()
            writeChannel.send(WriteOp.LoadComplete(staging))

            val pendingDirty = LinkedHashSet<String>()
            // spec § 4.2 W3 修复(M3):"距首个 dirty 100ms 内 flush",而不是依赖后续 op 唤醒。
            // firstDirtyAt 记录"当前 pendingDirty 批次的第一条入队时刻";为 0 表示无待 flush。
            // actor 在 select 中带 onTimeout(remaining),即使没有新 op 也会被 timeout 唤醒 flush。
            var firstDirtyAt = 0L

            try {
                while (true) {
                    val timeoutMs = if (firstDirtyAt > 0L) {
                        (firstDirtyAt + debounceMs - clock()).coerceAtLeast(0L)
                    } else {
                        Long.MAX_VALUE
                    }

                    val received: WriteOp? = select {
                        writeChannel.onReceiveCatching { result ->
                            result.getOrNull()
                        }
                        if (timeoutMs != Long.MAX_VALUE) {
                            onTimeout(timeoutMs) {
                                null  // null = 视作 timeout 触发,继续走 flush 检查
                            }
                        }
                    }

                    if (received == null) {
                        // 通道未关闭情况下的 null 视作 timeout(timeoutMs 路径返回 null);
                        // 通道关闭时 onReceiveCatching 返回 closed,getOrNull 也是 null,
                        // 但这种情况后续 trySend 会失败,actor 退出循环依赖 channel 状态。
                        if (writeChannel.isClosedForReceive) break
                        // timeout 触发:flush pendingDirty
                        if (pendingDirty.isNotEmpty()) {
                            flushToDisk(pendingDirty)
                            pendingDirty.clear()
                            firstDirtyAt = 0L
                        }
                        continue
                    }

                    when (received) {
                        is WriteOp.Put -> {
                            applyPut(received)
                            if (pendingDirty.isEmpty()) firstDirtyAt = clock()
                            pendingDirty.add(received.packageName)
                        }
                        is WriteOp.Invalidate -> {
                            hot.removePackage(received.packageName)
                            if (pendingDirty.isEmpty()) firstDirtyAt = clock()
                            pendingDirty.add(received.packageName)
                        }
                        is WriteOp.LoadComplete -> {
                            applyLoadComplete(received.staging)
                            loadCompleted = true
                            // LoadComplete 不入 pendingDirty(磁盘→内存的单向 op)
                        }
                        is WriteOp.Flush -> {
                            if (pendingDirty.isNotEmpty()) {
                                flushToDisk(pendingDirty)
                                pendingDirty.clear()
                                firstDirtyAt = 0L
                            }
                            received.ack.complete(Unit)
                            continue
                        }
                    }

                    // 批次阈值:pendingDirty 累积到 debounceMaxBatch 包立即 flush
                    if (pendingDirty.size >= debounceMaxBatch) {
                        flushToDisk(pendingDirty)
                        pendingDirty.clear()
                        firstDirtyAt = 0L
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // channel 关闭 → actor 退出
            }
        }
    }

    /**
     * 读路径(无锁)。返回 hotCopy 中已有的接口签名表,未命中 → null。
     *
     * 命中条件:
     *   1. `hot.meta[pkg]` 存在
     *   2. cached version + lastUpdateTime 与传入参数一致
     *   3. `hot.interfaces[pkg][interfaceName]` 存在
     *
     * 任一不满足 → 返回 null。版本失配时 caller 应 [invalidate] 该包。
     */
    fun get(
        packageName: String,
        interfaceName: String,
        currentVer: Int,
        currentMtime: Long,
    ): Map<String, MethodSignature>? {
        val meta = hot.meta[packageName] ?: return null
        if (meta.versionCode != currentVer || meta.lastUpdateTime != currentMtime) {
            return null
        }
        return hot.get(packageName, interfaceName)
    }

    /**
     * 跳过版本校验直接读 hotCopy。
     *
     * 用途:[com.btrace.viewer.parser.MethodResolver.resolveSingleCandidate] 区分
     * "整个包从未写过"vs "有旧记录但版本失配"——版本失配时调 [invalidate] 把
     * stale 条目清掉(spec § 4.3 命中但版本不一致 → Invalidate op + 走反射)。
     */
    fun peekRaw(packageName: String, interfaceName: String): Map<String, MethodSignature>? =
        hot.get(packageName, interfaceName)

    /** 测试别名,语义同 [peekRaw]。 */
    internal fun peek(packageName: String, interfaceName: String): Map<String, MethodSignature>? =
        peekRaw(packageName, interfaceName)

    internal fun peekPackageMeta(packageName: String): AppClassLoaderRegistry.PackageMeta? =
        hot.meta[packageName]

    fun put(
        packageName: String,
        interfaceName: String,
        versionCode: Int,
        lastUpdateTime: Long,
        sig: Map<String, MethodSignature>,
    ) {
        if (sig.isEmpty()) return
        writeChannel.trySend(
            WriteOp.Put(packageName, interfaceName, versionCode, lastUpdateTime, sig),
        )
    }

    fun invalidate(packageName: String) {
        writeChannel.trySend(WriteOp.Invalidate(packageName))
    }

    /**
     * spec § 4.2:发 Flush op 等待 ack,返回时磁盘已落。
     *
     * 典型调用方:`ApplicationLifecycleObserver.onPause`
     * → `lifecycleScope.launch { persistentCache.flushNow() }`
     */
    suspend fun flushNow() {
        val ack = CompletableDeferred<Unit>()
        writeChannel.send(WriteOp.Flush(ack))
        ack.await()
    }

    /**
     * 测试钩子:阻塞当前协程直到 LoadComplete op 已被 actor 处理。
     */
    internal suspend fun awaitLoadComplete() {
        while (!loadCompleted) {
            kotlinx.coroutines.delay(1)
        }
    }

    private fun applyPut(op: WriteOp.Put) {
        // 包级元数据 → 直接覆盖(运行时 Put 携带的元数据是当前真实状态)
        hot.putPackageMeta(op.packageName, op.versionCode, op.lastUpdateTime)
        hot.put(op.packageName, op.interfaceName, op.sig, clock())
    }

    private fun applyLoadComplete(staging: Map<String, PackageData>) {
        val now = clock()
        for ((pkg, pkgData) in staging) {
            for ((iface, sig) in pkgData.interfaces) {
                if (!hot.contains(pkg, iface)) {
                    hot.put(pkg, iface, sig, now)
                }
            }
            // 包级元数据 merge:hotCopy 已有元数据则不覆盖(已 Put 优先)
            if (!hot.hasPackageMeta(pkg)) {
                hot.putPackageMeta(pkg, pkgData.version, pkgData.lastUpdated)
            }
        }
    }

    /**
     * 序列化 hotCopy 中 [dirty] 标记的整包(包级元数据 + 全部接口签名),与已有 JSON merge 后写回。
     *
     * 已被 Invalidate 的包从 JSON 中删除。
     */
    private fun flushToDisk(dirty: Set<String>) {
        try {
            // 1) 读出现有 JSON(包含我们没改过的其它包,保证不丢失)
            val merged = readDiskJsonForMergeOrEmpty()
            val packagesObj = merged.optJSONObject("packages") ?: JSONObject().also {
                merged.put("packages", it)
            }

            // 2) 把 dirty 包重写
            for (pkg in dirty) {
                val meta = hot.meta[pkg]
                val ifaceMap = hot.interfaces[pkg]
                if (meta == null || ifaceMap == null || ifaceMap.isEmpty()) {
                    // 已被 Invalidate / 还没 ready → 从 JSON 中删
                    packagesObj.remove(pkg)
                    continue
                }

                val pkgObj = JSONObject()
                pkgObj.put("version", meta.versionCode)
                pkgObj.put("lastUpdated", meta.lastUpdateTime)

                val ifacesObj = JSONObject()
                for ((iface, sigMap) in ifaceMap) {
                    val methodsObj = JSONObject()
                    for ((mname, sig) in sigMap) {
                        val m = JSONObject()
                        // code 字段:spec schema 规定要写,但 MethodSignature 不直接持有 code。
                        // 这里写 -1 占位 — load 时也不依赖该字段,仅作 future-proof。
                        m.put("code", -1)
                        m.put("paramTypes", org.json.JSONArray(sig.paramTypes))
                        m.put("returnType", sig.returnType ?: JSONObject.NULL)
                        methodsObj.put(mname, m)
                    }
                    ifacesObj.put(iface, methodsObj)
                }
                pkgObj.put("interfaces", ifacesObj)

                // 估算单包字节数,触发 maxBytesPerPackage 截断逻辑
                val pkgJson = pkgObj.toString()
                if (pkgJson.length > maxBytesPerPackage) {
                    truncatePackageInterfaces(pkg, pkgObj, ifacesObj, ifaceMap)
                }

                packagesObj.put(pkg, pkgObj)
            }

            // 3) maxPackages LRU evict(按 hot.lastAccess)
            evictPackagesIfOverflow(packagesObj)

            // 4) totalSoftLimit:计算总字节数,超过则按 lastAccess 继续淘汰最老
            evictTotalIfOverflow(packagesObj)

            merged.put("schemaVersion", CURRENT_SCHEMA_VERSION)

            prefs.edit().putString(KEY, merged.toString()).apply()
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "flushToDisk 异常: ${t.message}", t)
        }
    }

    /**
     * 单包字节数超 [maxBytesPerPackage] 时,按 lastAccess 移除最老接口直至落到上限内。
     * 至少保留 1 个接口(避免空包)。
     */
    private fun truncatePackageInterfaces(
        pkg: String,
        pkgObj: JSONObject,
        ifacesObj: JSONObject,
        ifaceMap: Map<String, Map<String, MethodSignature>>,
    ) {
        // 接口级 lastAccess 我们没维护;退化策略:按 ifaceMap 迭代序删除头部(最老)。
        val keys = ifaceMap.keys.toMutableList()
        while (pkgObj.toString().length > maxBytesPerPackage && ifacesObj.length() > 1) {
            val victim = keys.removeFirstOrNull() ?: break
            ifacesObj.remove(victim)
            CLogUtils.i(
                TAG,
                "telemetry cache=signature_disk level=entry reason=bytes_overflow " +
                    "pkg=$pkg iface=$victim " +
                    "entryCount=${ifacesObj.length()} bytes=${pkgObj.toString().length} " +
                    "maxEntries=$maxEntries maxPackages=$maxPackages " +
                    "maxBytesPerPackage=$maxBytesPerPackage totalSoftLimitBytes=$totalSoftLimitBytes",
            )
        }
    }

    /**
     * 包数超 [maxPackages] 时,按 hot.lastAccess 升序淘汰整包。
     */
    private fun evictPackagesIfOverflow(packagesObj: JSONObject) {
        if (packagesObj.length() <= maxPackages) return
        val keys = packagesObj.keys().asSequence().toMutableList()
        // 升序:最老的优先淘汰
        keys.sortBy { hot.lastAccess[it] ?: 0L }
        while (packagesObj.length() > maxPackages && keys.isNotEmpty()) {
            val victim = keys.removeFirstOrNull() ?: break
            packagesObj.remove(victim)
            hot.removePackage(victim)
            val pkgJsonLen = (packagesObj.optJSONObject(victim)?.toString()?.length ?: 0)
            CLogUtils.i(
                TAG,
                "telemetry cache=signature_disk level=package reason=lru_evict " +
                    "pkg=$victim iface=null " +
                    "entryCount=${packagesObj.length()} bytes=$pkgJsonLen " +
                    "maxEntries=$maxEntries maxPackages=$maxPackages " +
                    "maxBytesPerPackage=$maxBytesPerPackage totalSoftLimitBytes=$totalSoftLimitBytes",
            )
        }
    }

    private fun evictTotalIfOverflow(packagesObj: JSONObject) {
        var totalLen = packagesObj.toString().length.toLong()
        if (totalLen <= totalSoftLimitBytes) return
        val keys = packagesObj.keys().asSequence().toMutableList()
        keys.sortBy { hot.lastAccess[it] ?: 0L }
        while (totalLen > totalSoftLimitBytes && keys.isNotEmpty()) {
            val victim = keys.removeFirstOrNull() ?: break
            packagesObj.remove(victim)
            hot.removePackage(victim)
            totalLen = packagesObj.toString().length.toLong()
            CLogUtils.i(
                TAG,
                "telemetry cache=signature_disk level=package reason=bytes_overflow " +
                    "pkg=$victim iface=null " +
                    "entryCount=${packagesObj.length()} bytes=$totalLen " +
                    "maxEntries=$maxEntries maxPackages=$maxPackages " +
                    "maxBytesPerPackage=$maxBytesPerPackage totalSoftLimitBytes=$totalSoftLimitBytes",
            )
        }
    }

    private fun readDiskJsonForMergeOrEmpty(): JSONObject {
        val raw = prefs.getString(KEY, null) ?: return JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("packages", JSONObject())
        }
        return try {
            val obj = JSONObject(raw)
            val v = obj.optInt("schemaVersion", -1)
            if (v != CURRENT_SCHEMA_VERSION) {
                JSONObject().apply {
                    put("schemaVersion", CURRENT_SCHEMA_VERSION)
                    put("packages", JSONObject())
                }
            } else obj
        } catch (_: Throwable) {
            JSONObject().apply {
                put("schemaVersion", CURRENT_SCHEMA_VERSION)
                put("packages", JSONObject())
            }
        }
    }

    /**
     * 启动时从 SharedPreferences 解出 staging map。
     *
     * schemaVersion 校验:< 1 / > 1 整体丢弃。
     */
    private fun parseJsonFromDisk(): Map<String, PackageData> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            parseJsonString(raw)
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "parseJsonFromDisk 异常: ${t.message}", t)
            emptyMap()
        }
    }

    /**
     * 纯函数版,内部测试可直接构造 JSON 字符串验证。
     */
    internal fun parseJsonString(json: String): Map<String, PackageData> {
        val obj = try { JSONObject(json) } catch (_: Throwable) { return emptyMap() }
        val schemaVersion = obj.optInt("schemaVersion", -1)
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            CLogUtils.w(TAG, "schemaVersion=$schemaVersion 不等于 $CURRENT_SCHEMA_VERSION,整体丢弃")
            return emptyMap()
        }
        val packages = obj.optJSONObject("packages") ?: return emptyMap()
        val out = HashMap<String, PackageData>()
        val pkgKeys = packages.keys()
        while (pkgKeys.hasNext()) {
            val pkg = pkgKeys.next()
            val pkgObj = packages.optJSONObject(pkg) ?: continue
            val version = pkgObj.optInt("version", -1)
            val lastUpdated = pkgObj.optLong("lastUpdated", -1L)
            val ifacesObj = pkgObj.optJSONObject("interfaces") ?: continue
            val interfaces = HashMap<String, Map<String, MethodSignature>>()
            val ifaceKeys = ifacesObj.keys()
            while (ifaceKeys.hasNext()) {
                val iface = ifaceKeys.next()
                val methodsObj = ifacesObj.optJSONObject(iface) ?: continue
                val methods = HashMap<String, MethodSignature>()
                val methodKeys = methodsObj.keys()
                while (methodKeys.hasNext()) {
                    val mname = methodKeys.next()
                    val mobj = methodsObj.optJSONObject(mname) ?: continue
                    val paramTypesArr = mobj.optJSONArray("paramTypes")
                    val params = if (paramTypesArr == null) emptyList() else {
                        (0 until paramTypesArr.length()).mapNotNull {
                            try { paramTypesArr.getString(it) } catch (_: Throwable) { null }
                        }
                    }
                    val returnType = if (mobj.isNull("returnType")) null else mobj.optString("returnType", "")
                        .takeIf { it.isNotEmpty() }
                    methods[mname] = MethodSignature(mname, params, returnType)
                }
                if (methods.isNotEmpty()) interfaces[iface] = methods
            }
            if (interfaces.isNotEmpty()) {
                out[pkg] = PackageData(version = version, lastUpdated = lastUpdated, interfaces = interfaces)
            }
        }
        return out
    }
}
