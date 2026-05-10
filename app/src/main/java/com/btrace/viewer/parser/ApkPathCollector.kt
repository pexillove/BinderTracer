package com.btrace.viewer.parser

/**
 * spec § 6.4 / review 2026-05-02 [P1] —— split APK 双路径加载的"路径收集 + 拼接"纯逻辑。
 *
 * 当前 [InterfaceIndexBuilder] 和 [AppClassLoaderRegistry] 都只用 `ai.sourceDir`(也就是 base.apk),
 * 但主流大型 App(微信/抖音/淘宝/小红书 等)都已经走 split APK 安装,代码可能落在
 * `ai.splitSourceDirs` 指向的若干 `split_xxx.apk` 里。如果 `$Stub` 落在 split,两条链路都会 miss。
 *
 * 把"按 base + splits 收集路径"和"拼成 PathClassLoader / DexClassLoader 能吃的 dexPath"两步抽到这里,
 * 是为了:
 *   1. 单测能直接覆盖,不依赖 PackageManager / Robolectric;
 *   2. 两个调用方共用同一份 null-safe 行为(splitSourceDirs 类型是 `Array<String>?`,可能为 null,
 *      数组里也可能塞空串)。
 *
 * Android `PathClassLoader` / `DexClassLoader` 的 `dexPath` 用 ":" 分隔多 dex 文件,这里直接用这个约定。
 */
internal object ApkPathCollector {

    private const val DEX_PATH_SEPARATOR = ":"

    /**
     * 收集 [sourceDir] + [splitSourceDirs] 的有效 APK 路径集合。
     *
     * - sourceDir 为 null/空 时跳过(理论上正常包不会,但 PackageManager 偶发会返回怪结果)
     * - splits 为 null 时只返回 base
     * - splits 数组里的 null / 空白条目会被过滤
     * - sourceDir 重复出现在 splits 里时只保留一次,顺序按"base 优先,splits 原顺序"
     *
     * 返回的列表保留顺序,base.apk 总是排在最前 —— 接口主类落在 base 的概率最高,前置可让
     * `PathClassLoader.findClass` 的线性查找更快命中。
     */
    fun collectApkPaths(sourceDir: String?, splitSourceDirs: Array<String?>?): List<String> {
        val out = LinkedHashSet<String>()
        sourceDir?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        splitSourceDirs?.forEach { p ->
            if (p != null && p.isNotBlank()) out.add(p)
        }
        return out.toList()
    }

    /**
     * 把 [paths] 用 ":" 拼成 `PathClassLoader` / `DexClassLoader` 的 dexPath 字符串。
     * 空列表返回空串(调用方自己判断是否 fallback)。
     */
    fun joinDexPath(paths: List<String>): String = paths.joinToString(DEX_PATH_SEPARATOR)

    /**
     * `AppClassLoaderRegistry` 专用的拼接:base 已经被 root 复制到本 App 私有目录(隔离目标 App 升级/卸载),
     * splits 走"原路径不复制"。这样可以避开"几十个 split 一起复制"的成本(微信主包 200MB+ split 几十个),
     * 代价是:目标 App 升级/卸载会让原 split 路径失效 —— 由调用方在 `DexClassLoader` 构造时 try/catch 容忍,
     * 失效就 fallback 到只用 [copiedBase]。
     */
    fun composeLoaderDexPath(copiedBase: String, originalSplits: Array<String?>?): String {
        val paths = collectApkPaths(copiedBase, originalSplits)
        return joinDexPath(paths)
    }
}
