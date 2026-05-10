package com.btrace.viewer.parser

import com.btrace.viewer.utils.CLogUtils

/**
 * 方法签名缓存(内存层,spec 2026-05-03 § 4.4)。
 *
 * - key = (packageName ?: "", interfaceName) —— 不同 caller App 的同名 interface 各占一格,
 *   避免框架 interface 在 caller App 上下文中被 framework loader 的空结果污染。
 * - value = `Map<methodName, MethodSignature>`,MethodSignature 自带 methodName + paramTypes + returnType。
 * - **不缓存空结果**:`getOrLoad` 在 loader 返回 `emptyMap()` 时不写入,下次再调还会重试。
 *   这是 review #2 的核心修复:预热前的首批事件不能把空记录永久锁死。
 *   注意:returnType=null 但 paramTypes 已填的条目**不是**空结果,会被正常缓存。
 * - `invalidatePackage` 在 `AppClassLoaderRegistry` 预热完成后由 caller 调用,清掉该包下所有
 *   接口的空缓存(实际上空缓存根本没写入,这里主要清"用 system loader 跑出非空但残缺"的条目)。
 *
 * spec 2026-05-03 § 4.4:LRU 上限 [maxEntries](默认 [DEFAULT_MAX_ENTRIES] = 256,可调
 * 范围 64..1024)。超过上限时按访问顺序淘汰最老条目并打 telemetry。**LRU evict 不触发持久
 * 缓存写入**(I4 不变量):内存淘汰只是把 hot copy 释放,下次再用从持久缓存重新 load。
 */
class SignatureCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    companion object {
        private const val TAG = "SignatureCache"
        const val DEFAULT_MAX_ENTRIES: Int = 256
        const val MIN_MAX_ENTRIES: Int = 64
        const val MAX_MAX_ENTRIES: Int = 1024
    }

    private data class Key(val packageBucket: String, val interfaceName: String)

    private val lock = Any()

    // accessOrder=true 让 LinkedHashMap 在 get/put 后把命中条目移到 tail,
    // 因此 entries.next()(head)总是最久未访问的,直接 evict 即可。
    private val store = object : LinkedHashMap<Key, Map<String, MethodSignature>>(
        16, 0.75f, /* accessOrder = */ true,
    ) {}

    private fun bucketOf(packageName: String?): String = packageName ?: ""

    fun put(packageName: String?, interfaceName: String, signature: MethodSignature) {
        val key = Key(bucketOf(packageName), interfaceName)
        synchronized(lock) {
            val merged = (store[key] ?: emptyMap()).toMutableMap().also {
                it[signature.methodName] = signature
            }
            store[key] = merged
            evictIfOverflow()
        }
    }

    fun get(packageName: String?, interfaceName: String, methodName: String): MethodSignature? =
        synchronized(lock) {
            store[Key(bucketOf(packageName), interfaceName)]?.get(methodName)
        }

    /**
     * 命中即返回;未命中调 [loader] 拉数据,**仅当返回非空时**写入缓存。
     *
     * 线程安全契约:check-then-act 内部加锁。loader 在锁外执行(避免长时反射阻塞读者),
     * 两个并发 caller 同时 miss 时 loader 可能被调两次;这是 *有意* 的设计,因为本类的
     * loader 都是反射读取(确定性、无副作用),双重执行只会浪费一点 CPU,不会破坏正确性。
     */
    fun getOrLoad(
        packageName: String?,
        interfaceName: String,
        loader: (String?, String) -> Map<String, MethodSignature>,
    ): Map<String, MethodSignature> {
        val key = Key(bucketOf(packageName), interfaceName)
        synchronized(lock) { store[key]?.let { return it } }

        val loaded = loader(packageName, interfaceName)
        if (loaded.isNotEmpty()) {
            synchronized(lock) {
                store[key] = loaded
                evictIfOverflow()
            }
        }
        return loaded
    }

    fun invalidatePackage(packageName: String) {
        val bucket = bucketOf(packageName)
        synchronized(lock) {
            store.keys.removeAll { it.packageBucket == bucket }
        }
    }

    /**
     * 仅供单测/诊断使用。
     */
    fun size(): Int = synchronized(lock) { store.size }

    /**
     * 容量超限时按 access-order 淘汰最老条目,逐条打 telemetry(11 字段格式,spec § 4.4)。
     *
     * 调用前必须已持有 [lock]。
     */
    private fun evictIfOverflow() {
        while (store.size > maxEntries) {
            val it = store.entries.iterator()
            if (!it.hasNext()) break
            val oldest = it.next()
            it.remove()
            CLogUtils.i(
                TAG,
                "telemetry cache=signature_mem level=entry reason=lru_evict " +
                    "pkg=${oldest.key.packageBucket} iface=${oldest.key.interfaceName} " +
                    "entryCount=${store.size} bytes=null " +
                    "maxEntries=$maxEntries maxPackages=null " +
                    "maxBytesPerPackage=null totalSoftLimitBytes=null",
            )
        }
    }
}
