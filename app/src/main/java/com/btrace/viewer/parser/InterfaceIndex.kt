package com.btrace.viewer.parser

import android.content.Context
import android.content.SharedPreferences
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * spec § 6.4 第 1 档 —— APK 级"接口名 → 包名"反查目录。
 *
 * 当 [MethodResolver] 的前三档(system reflection / caller ClassLoader / static methods.json)
 * 都没命中时,本类提供一份"接口名 → 候选包名集合"的索引,让 resolver 可以按图索骥去
 * 加载那些包的 DexClassLoader 反射 `$Stub`。
 *
 * 索引由 [InterfaceIndexBuilder] 在后台异步构建并刷新,这里只保存结果 + 持久化到 SharedPreferences,
 * 应用冷启动时直接拿持久化版本先用,后台再增量重建(见 builder)。
 *
 * 实现:
 * - 内存表用 [ConcurrentHashMap],值是 [MutableSet] 以便去重。
 * - 持久化用 SharedPreferences("btrace_interface_index"),value 是单条 JSONObject 字符串。
 * - 读写持久化都在 [Dispatchers.IO],绝不阻塞调用方;查询是纯内存,O(1)。
 */
@Singleton
class InterfaceIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "InterfaceIndex"
        private const val PREFS_NAME = "btrace_interface_index"
        private const val KEY_INDEX = "interface_index"
    }

    // interfaceName -> packageNames(按首次插入顺序保留,spec 2026-05-03 § 3.2)。
    //
    // 用 LinkedHashMap + LinkedHashSet 保证:
    //   - 同一接口下多个候选包的"首次见到"顺序稳定,lookupOrdered 直接返回插入序;
    //   - 旧 lookup() 走 toSet(),语义不变(无序集合)。
    //
    // 并发性:本类的写入(addEntry / clear)由 InterfaceIndexBuilder 串行调用,读取
    // (lookup / lookupOrdered)在多线程访问。所有读写都通过本类的 lock 同步,以保证
    // LinkedHashMap 在 iterate / put 之间不会触发 ConcurrentModificationException。
    private val lock = Any()
    private val table = LinkedHashMap<String, LinkedHashSet<String>>()

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 查询接口名对应的候选包名集合(无序)。未命中返回 emptySet。
     *
     * 兼容 API:旧调用方仅依赖集合语义。需要顺序时用 [lookupOrdered]。
     */
    fun lookup(interfaceName: String): Set<String> {
        return lookupOrdered(interfaceName).toSet()
    }

    /**
     * 查询接口名对应的候选包名,按首次插入顺序返回(spec 2026-05-03 § 3.2)。
     * 未命中返回 emptyList。
     */
    fun lookupOrdered(interfaceName: String): List<String> = synchronized(lock) {
        val entry = table[interfaceName] ?: return emptyList()
        // 拷贝一份给调用方,避免外部 iterate 时和 builder 写入冲突
        return entry.toList()
    }

    /**
     * 加一条索引。同一 (interfaceName, packageName) 重复 add 幂等,集合自动去重。
     */
    fun addEntry(interfaceName: String, packageName: String) {
        if (interfaceName.isEmpty() || packageName.isEmpty()) return
        synchronized(lock) {
            table.getOrPut(interfaceName) { LinkedHashSet() }.add(packageName)
        }
    }

    /**
     * 整张索引清空。每次 builder 触发增量重建前先 clear,避免被卸载/换签的包留垃圾。
     */
    fun clear() {
        synchronized(lock) { table.clear() }
    }

    /**
     * 当前索引大小(接口数)。仅供 builder 日志/单测使用。
     */
    fun size(): Int = synchronized(lock) { table.size }

    /**
     * 异步把当前内存表序列化到 SharedPreferences。在 [Dispatchers.IO] 跑。
     */
    suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            // 锁内拷贝一份快照,锁外做 JSON 序列化与磁盘 I/O,避免 SP commit 阻塞写者。
            val snapshot: Map<String, Set<String>> = synchronized(lock) {
                table.entries.associate { (iface, pkgs) -> iface to pkgs.toSet() }
            }
            val json = serialize(snapshot)
            prefs.edit().putString(KEY_INDEX, json).apply()
            CLogUtils.i(TAG, "persist() 写入 ${snapshot.size} 个接口")
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "persist() 序列化失败: ${t.message}", t)
        }
    }

    /**
     * 异步从 SharedPreferences 装载持久化的索引到内存表。在 [Dispatchers.IO] 跑。
     * 装载完成后调用方通常会再触发后台增量重建。
     */
    suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        try {
            val json = prefs.getString(KEY_INDEX, null) ?: return@withContext
            val parsed = parseJson(json)
            synchronized(lock) {
                for ((iface, pkgs) in parsed) {
                    table.getOrPut(iface) { LinkedHashSet() }.addAll(pkgs)
                }
            }
            CLogUtils.i(TAG, "loadFromDisk() 装载 ${parsed.size} 个接口")
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "loadFromDisk() 反序列化失败: ${t.message}", t)
        }
    }

    /**
     * 把内存表序列化成 JSON 字符串。**纯函数 / 不读 Context** —— 给单测 + persist 复用。
     *
     * 输出格式:`{ "<interfaceName>": ["<pkg1>", "<pkg2>", ...], ... }`
     */
    internal fun serialize(map: Map<String, Set<String>>): String {
        val obj = JSONObject()
        for ((iface, pkgs) in map) {
            val arr = JSONArray()
            for (p in pkgs.sorted()) arr.put(p)   // sorted 让序列化结果可重现,便于单测断言
            obj.put(iface, arr)
        }
        return obj.toString()
    }

    /**
     * 把 JSON 反序列化成 `Map<interfaceName, Set<packageName>>`。**纯函数** —— 给单测 + load 复用。
     *
     * 行为:
     *   - JSON 顶层不是 object → 返回空表
     *   - 单条 value 不是数组 → 跳过该条
     *   - 任意级别抛异常 → 返回空表(防御式)
     */
    internal fun parseJson(json: String): Map<String, Set<String>> {
        val result = HashMap<String, MutableSet<String>>()
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val iface = keys.next()
                val arr = obj.optJSONArray(iface) ?: continue
                val pkgs = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val pkg = try { arr.getString(i) } catch (_: Throwable) { continue }
                    if (pkg.isNotEmpty()) pkgs.add(pkg)
                }
                if (pkgs.isNotEmpty()) result[iface] = pkgs
            }
        } catch (_: Throwable) {
            return emptyMap()
        }
        return result
    }
}
