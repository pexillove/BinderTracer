package com.btrace.viewer.parser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * spec § 6.4 第 1 档 + § 10 未决问题 #4 —— 异步扫描已安装 App 的 dex,
 * 把每个含 `DESCRIPTOR` 静态字段的 `XxxxXxx$Stub` 提取出来,落进 [InterfaceIndex]。
 *
 * 范围限制(spec § 10 #4):**只扫系统 App + 当前已被监控选过的 App**。
 * 全量扫所有用户 App 在低端机冷启动会卡好几秒,所以只在用户主动用过的包上扫。
 *
 * 触发:
 *   1. 应用启动时 [scanAsync],先 [InterfaceIndex.loadFromDisk] 拿持久化版本即时可用,
 *      然后后台增量重建。
 *   2. 增量重建包含 `clear()` —— 卸载/换签的包不会留垃圾。
 *
 * 失败隔离:每个 App 的 dex 扫描包在 try-catch 里,某个 App 挂掉不影响其它 App。
 * DexFile 在新版本 Android 已 deprecated,异常时降级到只扫 sourceDir 文件名(几乎不可能识别 stub,
 * 但至少不 crash)。
 */
@Singleton
class InterfaceIndexBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val interfaceIndex: InterfaceIndex,
    private val methodResolver: MethodResolver,
    private val appClassLoaderRegistry: AppClassLoaderRegistry
) {
    companion object {
        private const val TAG = "InterfaceIndexBuilder"
        private const val STUB_SUFFIX = "\$Stub"
        private const val DESCRIPTOR_FIELD = "DESCRIPTOR"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var scanInProgress = false

    /**
     * 异步触发扫描。fire-and-forget,不阻塞调用方。
     *
     * 流程:
     *   1. 先从 SharedPreferences 装载持久化索引 → 立即可用
     *   2. clear() + 全量重建(系统 App + monitoredPackages)
     *   3. persist() 写回 SharedPreferences
     *   4. 通知 [MethodResolver] 失效相关缓存(目前用 invalidatePackage 对每个新增包名调一次)
     *
     * 重复调用是幂等的:第二次进来发现 [scanInProgress] 就直接返回。
     */
    fun scanAsync() {
        if (scanInProgress) {
            CLogUtils.d(TAG, "scanAsync() 已在进行中,跳过")
            return
        }
        scanInProgress = true
        scope.launch {
            try {
                // 1) 先尝试从磁盘装载,让查询立即可用(即使后续重建失败)
                interfaceIndex.loadFromDisk()
                CLogUtils.i(TAG, "scanAsync() loadFromDisk 完成,size=${interfaceIndex.size()}")

                // 2) 清空并重建
                interfaceIndex.clear()
                val candidatePackages = collectCandidatePackages()
                CLogUtils.i(TAG, "scanAsync() 候选包数 = ${candidatePackages.size}")

                var totalStubs = 0
                for (pkg in candidatePackages) {
                    try {
                        // spec 2026-05-03 § 4.2 PM 边界:本类调 getApplicationInfo 只为
                        // 拿 sourceDir 扫描 dex,**不**读 versionCode/lastUpdateTime。
                        // 拿"版本元数据"的位置仍只在 AppClassLoaderRegistry 内部两处
                        // (buildClassLoader + readPackageMetaFromPm),与 spec §4.2
                        // 末尾的不变量一致;spec §12 字面 grep `PackageManager.*getPackageInfo`
                        // 也不会命中本类。
                        val ai = context.packageManager.getApplicationInfo(pkg, 0)
                        val stubs = scanPackage(pkg, ai)
                        for (iface in stubs) {
                            interfaceIndex.addEntry(iface, pkg)
                            totalStubs++
                        }
                    } catch (t: Throwable) {
                        // 单个 App 挂掉不影响其它 —— 继续下一个
                        CLogUtils.w(TAG, "scanPackage($pkg) 失败: ${t.message}")
                    }
                }
                CLogUtils.i(
                    TAG,
                    "scanAsync() 完成: ${interfaceIndex.size()} 个接口, $totalStubs 条记录"
                )

                // 3) 持久化
                interfaceIndex.persist()

                // 4) 让 MethodResolver 失效已知监控包的缓存,这样下次反射会走第 4 档
                for (pkg in appClassLoaderRegistry.monitoredPackages()) {
                    methodResolver.invalidatePackage(pkg)
                }
            } catch (t: Throwable) {
                CLogUtils.e(TAG, "scanAsync() 顶层异常: ${t.message}", t)
            } finally {
                scanInProgress = false
            }
        }
    }

    /**
     * review 2026-05-02 P1 —— 监控启动握手成功后,只对 *单个* packageName 做一次增量索引,
     * 避免新包要等下一轮全量 [scanAsync] 才进 [InterfaceIndex]。
     *
     * 与 [scanAsync] 的差别:
     *   1. 不调 [InterfaceIndex.clear]:全量 scan 是"重建",这里是"补一行"。其它包的索引不能被擦掉。
     *   2. 不重新枚举所有已安装包,只读这一个 packageName 的 [ApplicationInfo] + sourceDir。
     *   3. 同步落盘([InterfaceIndex.persist] 在 [Dispatchers.IO]),让冷启动直接读到最新版本。
     *
     * 幂等性:同一个 packageName 多次调用,最终 [InterfaceIndex] 状态稳定 ——
     * [InterfaceIndex.addEntry] 用 [java.util.concurrent.ConcurrentHashMap.newKeySet],天然集合去重。
     *
     * 异常容忍:DexFile 读失败 / 包不存在 / sourceDir 为空,catch 后 log warn,不向上抛 ——
     * 监控启动流程不能因为索引补建失败而崩。
     *
     * **不处理 splitSourceDirs**(那是 review P1 第二条 / task #18 的范围)。这里只读 sourceDir。
     */
    suspend fun scanPackageAsync(packageName: String) {
        if (packageName.isEmpty()) return
        try {
            val descriptors = withContext(Dispatchers.IO) {
                // spec 2026-05-03 § 4.2 PM 边界:同上,只用 sourceDir,不读版本元数据。
                val ai = try {
                    context.packageManager.getApplicationInfo(packageName, 0)
                } catch (t: Throwable) {
                    CLogUtils.w(TAG, "scanPackageAsync($packageName) getApplicationInfo 失败: ${t.message}")
                    return@withContext emptyList<String>()
                }
                try {
                    scanPackage(packageName, ai)
                } catch (t: Throwable) {
                    CLogUtils.w(TAG, "scanPackageAsync($packageName) scanPackage 失败: ${t.message}")
                    emptyList()
                }
            }
            mergeStubsIntoIndex(interfaceIndex, packageName, descriptors)
            if (descriptors.isNotEmpty()) {
                CLogUtils.i(
                    TAG,
                    "scanPackageAsync($packageName) 增量补建 ${descriptors.size} 个接口,total=${interfaceIndex.size()}"
                )
                // 增量结果落盘 —— 让下次冷启动直接读到这条新增。空结果不写,避免无意义的 IO。
                interfaceIndex.persist()
            } else {
                CLogUtils.d(TAG, "scanPackageAsync($packageName) 没有命中任何 \$Stub,跳过 persist")
            }
        } catch (t: Throwable) {
            // 兜底:任何意外都不能让监控启动崩
            CLogUtils.w(TAG, "scanPackageAsync($packageName) 顶层异常: ${t.message}", t)
        }
    }

    /**
     * 候选包名:系统 App + 当前 [AppClassLoaderRegistry.monitoredPackages]。
     */
    private fun collectCandidatePackages(): Set<String> {
        val out = HashSet<String>()
        try {
            val packages = context.packageManager
                .getInstalledPackages(PackageManager.GET_PERMISSIONS)
            for (p in packages) {
                val ai = p.applicationInfo ?: continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    out.add(p.packageName)
                }
            }
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "getInstalledPackages 失败: ${t.message}")
        }
        // 即使系统包列表挂了,也要把已被监控选过的包加上 —— 这是用户最关心的那批
        out.addAll(appClassLoaderRegistry.monitoredPackages())
        return out
    }

    /**
     * 扫一个包的 dex,反射检测每个含 `$Stub` 的类的 `DESCRIPTOR` 静态字段。
     * 返回所有命中的 descriptor 字符串(即接口全限定名)。
     *
     * 实现:
     *   1. 收集 `ai.sourceDir + ai.splitSourceDirs`(review 2026-05-02 [P1] —— 否则 split APK 中的
     *      `$Stub` 会被漏抓)
     *   2. 对每个 path 单独 [DexFile] 枚举类名,任一 path 失败不阻塞其它(微信类巨包 split 几十个,
     *      个别 split 是 native-only / resource-only,DexFile 抛异常很正常)
     *   3. 把所有 path 用 ":" 拼成一个 `PathClassLoader` 的 dexPath,反射 DESCRIPTOR 静态字段
     */
    private fun scanPackage(packageName: String, ai: ApplicationInfo): List<String> {
        @Suppress("UNCHECKED_CAST")
        val apkPaths = ApkPathCollector.collectApkPaths(
            ai.sourceDir,
            ai.splitSourceDirs as Array<String?>?
        )
        if (apkPaths.isEmpty()) return emptyList()

        val classNames = ArrayList<String>()
        for (path in apkPaths) {
            try {
                @Suppress("DEPRECATION")
                val dex = DexFile(path)
                try {
                    val it = dex.entries()
                    while (it.hasMoreElements()) classNames.add(it.nextElement())
                } finally {
                    try { dex.close() } catch (_: Throwable) { }
                }
            } catch (t: Throwable) {
                // DexFile 在 P+ 上对部分 split apk 不友好(resource-only / native-only split),
                // 单 path 异常不影响其它 path
                CLogUtils.v(TAG, "DexFile($packageName, $path) 失败,跳过该 path: ${t.message}")
            }
        }
        if (classNames.isEmpty()) return emptyList()

        val stubCandidates = extractStubInterfaces(classNames)
        if (stubCandidates.isEmpty()) return emptyList()

        val dexPath = ApkPathCollector.joinDexPath(apkPaths)
        val classLoader = try {
            dalvik.system.PathClassLoader(dexPath, javaClass.classLoader)
        } catch (t: Throwable) {
            CLogUtils.v(TAG, "PathClassLoader($packageName, $dexPath) 失败: ${t.message}")
            return emptyList()
        }

        val out = ArrayList<String>(stubCandidates.size)
        for (stubName in stubCandidates) {
            val descriptor = try {
                val cls = Class.forName(stubName, false, classLoader)
                val field = cls.getDeclaredField(DESCRIPTOR_FIELD)
                field.isAccessible = true
                val v = field.get(null)
                if (v is String && v.isNotEmpty()) v else null
            } catch (_: Throwable) {
                null   // 单个类失败跳过(混淆 / hidden API / 不是 AIDL stub)
            }
            if (descriptor != null) out.add(descriptor)
        }
        if (out.isNotEmpty()) {
            CLogUtils.d(TAG, "scanPackage($packageName) 命中 ${out.size} 个 \$Stub")
        }
        return out
    }
}

/**
 * 把一批 descriptor(接口全限定名)幂等并入 [InterfaceIndex],绑定到给定 packageName。
 *
 * **纯函数,只动 [InterfaceIndex] 的内存表,不做反射 / IO** —— 单测直接调,不需要 Robolectric。
 *
 * 行为:
 *   - packageName 为空 → 直接 return,不污染索引
 *   - descriptors 空集 → no-op
 *   - 单条 descriptor 为空串 → 跳过([InterfaceIndex.addEntry] 内部已守一道,这里 fail-safe)
 *   - 重复调用幂等 —— [InterfaceIndex] 用 set 去重
 */
internal fun mergeStubsIntoIndex(
    index: InterfaceIndex,
    packageName: String,
    descriptors: List<String>
) {
    if (packageName.isEmpty()) return
    for (d in descriptors) {
        if (d.isEmpty()) continue
        index.addEntry(d, packageName)
    }
}

/**
 * 从一组类名里挑出"长得像 AIDL stub"的:即类名以 `$Stub` 结尾(且不是 `$Stub$Proxy` / `$Stub$xxx`)。
 *
 * **纯函数,无任何 Android / 反射依赖** —— 单测直接调,不需要 Robolectric。
 */
internal fun extractStubInterfaces(dexClassNames: List<String>): List<String> {
    val out = ArrayList<String>()
    for (name in dexClassNames) {
        if (!name.endsWith("\$Stub")) continue
        // 排除嵌套子类:只保留正好以 $Stub 结尾(不是 $Stub$XXX)的那一档
        // endsWith 已保证 ends with "$Stub";嵌套子类长这样:`...$Stub$Proxy`,会以 "$Proxy" 结尾,
        // 所以 endsWith("$Stub") 已经够。
        // 防御:`$Stub` 之前必须有内容,即不能是裸 `$Stub`
        if (name.length <= "\$Stub".length) continue
        out.add(name)
    }
    return out
}
