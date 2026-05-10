package com.btrace.viewer.parser

import android.content.Context
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 静态方法表 — spec § 6.4 第 2 档。
 *
 * 用途:对**已知接口名但反射拿不到 $Stub** 的情况(典型是 HIDL/IBase),提供 code → 方法名
 * 的静态映射。数据源是 `assets/methods.json`,格式:
 *
 *     { "<interfaceName>": { "<codeHexOrDec>": "<methodName>", ... }, ... }
 *
 * code 字符串支持十进制(`"42"`)和十六进制(`"0x00FFFFFF"`)两种,内部统一存成 Int。
 *
 * 加载策略:**首次 lookup 时 lazy 初始化**,失败(IO / JSON 损坏)写空表 + warn 日志,**绝不 crash**。
 */
@Singleton
class StaticMethodTable @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "StaticMethodTable"
        private const val ASSET_NAME = "methods.json"
    }

    @Volatile
    private var loaded = false

    // interfaceName → (code → methodName)
    private var table: Map<String, Map<Int, String>> = emptyMap()

    /**
     * 查表:命中返回方法名,未命中返回 null。
     */
    fun lookup(interfaceName: String, code: Int): String? {
        ensureLoaded()
        return table[interfaceName]?.get(code)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            table = try {
                val json = context.assets.open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() }
                val parsed = parseJson(json)
                CLogUtils.i(
                    TAG,
                    "ensureLoaded() 加载成功:${parsed.size} 个接口, " +
                        "${parsed.values.sumOf { it.size }} 个方法"
                )
                parsed
            } catch (t: Throwable) {
                CLogUtils.w(TAG, "ensureLoaded() 读取 assets/$ASSET_NAME 失败,使用空表: ${t.message}", t)
                emptyMap()
            }
            loaded = true
        }
    }

    /**
     * 纯函数:把 JSON 文本解析成 `Map<interfaceName, Map<code, methodName>>`。
     * **不依赖 Android Context / 不写日志** —— 给单测用。
     *
     * 行为:
     *   - JSON 顶层不是 object → 返回空表
     *   - 单个 interface 内某条 code 解析失败(非数字 / 越界) → 跳过该条,其他保留
     *   - 任意级别抛异常 → 返回空表(防御式)
     */
    internal fun parseJson(json: String): Map<String, Map<Int, String>> {
        val result = HashMap<String, Map<Int, String>>()
        try {
            val obj = JSONObject(json)
            val ifaceKeys = obj.keys()
            while (ifaceKeys.hasNext()) {
                val iface = ifaceKeys.next()
                val methods = obj.optJSONObject(iface) ?: continue
                val codeMap = HashMap<Int, String>()
                val codeKeys = methods.keys()
                while (codeKeys.hasNext()) {
                    val codeStr = codeKeys.next()
                    val code = try {
                        // Integer.decode 支持 "0x..", "0X..", "#..", "0.." (octal), 纯十进制
                        Integer.decode(codeStr)
                    } catch (_: NumberFormatException) {
                        continue
                    }
                    val name = try {
                        methods.getString(codeStr)
                    } catch (_: Throwable) {
                        continue
                    }
                    codeMap[code] = name
                }
                if (codeMap.isNotEmpty()) {
                    result[iface] = codeMap
                }
            }
        } catch (_: Throwable) {
            return emptyMap()
        }
        return result
    }
}
