package com.btrace.viewer.parser

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import com.btrace.viewer.utils.CLogUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单个参数的解码结果。
 *
 * - [status] = SUCCESS:`displayValue` 是已格式化的可读字符串
 * - [status] = UNPARSED:未知类型或 readXxx 抛异常 —— `errorMessage` 给原因
 *
 * spec § 10 规定的 `ParsedArgument` 即本类。字段名保持精简,序列化友好。
 */
data class DecodedArgument(
    val index: Int,
    val declaredType: String,
    val displayValue: String,
    val status: Status,
    val errorMessage: String? = null
) {
    enum class Status { SUCCESS, UNPARSED }
}

/**
 * 一次完整 Parcel payload 解码的结果。[args] 是按参数顺序的 DecodedArgument;
 * 因为 Parcel 是单向字节流,一旦某个参数解析失败,后续位置就不可信,这时 decoder
 * 会**立刻停下**把后续参数以 UNPARSED 状态占位(spec § 9.4:不丢事件)。
 */
data class ParcelDecodeResult(
    val args: List<DecodedArgument>,
    val consumed: Int,
    val remaining: Int,
    val fullySuccessful: Boolean
)

/**
 * 按方法签名(参数类型顺序)解码 Binder Parcel 的参数值。spec § 9.3。
 *
 * 依赖:
 *   - [MethodResolver.getMethodSignature] 拿到 paramTypes(例:["int","String","Bundle"])
 *   - 本类把 rawParcel 的字节恢复成一个 [Parcel] 实例、`setDataPosition` 到 arg 起点、
 *     按声明类型顺序 readXxx
 *
 * 不支持的类型或 readXxx 抛异常时,当前参数标 UNPARSED,**不再继续**解码(防止因位置
 * 错乱污染后续参数),但保留已成功的那批以及剩余字节供详情页看原始 hex。
 *
 * 注意:Parcel.unmarshall 内部会 memcpy 一份数据,对我们而言相当于只读快照 —— 不会
 * 修改调用方传入的 ByteArray。
 */
@Singleton
class ParcelArgumentDecoder @Inject constructor() {

    companion object {
        private const val TAG = "ParcelArgDecoder"
        private const val HEADER_LEN = 12
        private const val MAX_TOKEN_CHARS = 4096

        // 单字段显示截断:Bundle 里塞几 KB 字节的情况不少,列表摘要展示会很丑。
        private const val DISPLAY_MAX_LEN = 256

        /**
         * Framework Parcelable 白名单。Key 同时接受类型短名和 fully-qualified name;
         * Value = fully-qualified class name(给 Class.forName 用)。
         *
         * 只列 framework 内的、CREATOR 路径稳定可靠的类型。第三方 Parcelable 不收 ——
         * 它们需要目标 App ClassLoader,且短名容易撞名(比如多个 App 都有 `User`)。
         *
         * 注:Bundle/Intent/Uri/ComponentName/IBinder 已在 decodeValue 主 switch 里硬编码,
         * 不重复进白名单。
         */
        private val PARCELABLE_WHITELIST = mapOf(
            "Notification"          to "android.app.Notification",
            "PendingIntent"         to "android.app.PendingIntent",
            "UserHandle"            to "android.os.UserHandle",
            "WorkSource"            to "android.os.WorkSource",
            "Account"               to "android.accounts.Account",
            "Bitmap"                to "android.graphics.Bitmap",
            "Rect"                  to "android.graphics.Rect",
            "RectF"                 to "android.graphics.RectF",
            "Point"                 to "android.graphics.Point",
            "PointF"                to "android.graphics.PointF",
            "Location"              to "android.location.Location",
            "Messenger"             to "android.os.Messenger",
            "ParcelFileDescriptor"  to "android.os.ParcelFileDescriptor",
            "ClipData"              to "android.content.ClipData",
            "ApplicationInfo"       to "android.content.pm.ApplicationInfo",
            "PackageInfo"           to "android.content.pm.PackageInfo",
            "ActivityInfo"          to "android.content.pm.ActivityInfo",
            "ServiceInfo"           to "android.content.pm.ServiceInfo",
            "Configuration"         to "android.content.res.Configuration"
        )

        /** 反射拿到的 CREATOR 缓存,避免每次解析都 Class.forName + getField */
        private val creatorCache = ConcurrentHashMap<String, Parcelable.Creator<*>>()
    }

    fun decode(rawParcel: ByteArray, paramTypes: List<String>): ParcelDecodeResult {
        if (rawParcel.isEmpty() || paramTypes.isEmpty()) {
            return ParcelDecodeResult(emptyList(), 0, rawParcel.size, paramTypes.isEmpty())
        }

        val argStart = computeArgStart(rawParcel) ?: run {
            CLogUtils.v(TAG, "decode() 无法定位参数起点 (size=${rawParcel.size})")
            return ParcelDecodeResult(emptyList(), 0, rawParcel.size, false)
        }

        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(rawParcel, 0, rawParcel.size)
            parcel.setDataPosition(argStart)
            decodeFromParcel(parcel, paramTypes, totalSize = rawParcel.size)
        } finally {
            parcel.recycle()
        }
    }

    /**
     * 从一个已经 setDataPosition 好的 Parcel 按声明类型顺序解码参数。
     *
     * 抽出来主要给测试用 —— 真机字节布局的 12B header + UTF-16 token 在 Robolectric 的
     * ShadowParcel 上不成立(ShadowParcel 用 typed entries 自定义格式而非 native binary)。
     * 测试可以直接构造 Parcel、setDataPosition 后调用此方法。
     */
    internal fun decodeFromParcel(
        parcel: Parcel,
        paramTypes: List<String>,
        totalSize: Int = parcel.dataSize()
    ): ParcelDecodeResult {
        val results = ArrayList<DecodedArgument>(paramTypes.size)
        var allOk = true
        for ((i, type) in paramTypes.withIndex()) {
            val decoded = tryDecodeOne(parcel, i, type)
            results.add(decoded)
            if (decoded.status != DecodedArgument.Status.SUCCESS) {
                allOk = false
                // 剩余参数全部标未解析 —— 位置已乱,继续读只会错上加错
                for (j in (i + 1) until paramTypes.size) {
                    results.add(DecodedArgument(
                        index = j,
                        declaredType = paramTypes[j],
                        displayValue = "<已跳过:前一个参数解析失败>",
                        status = DecodedArgument.Status.UNPARSED,
                        errorMessage = "因前一参数解析失败而跳过"
                    ))
                }
                break
            }
        }
        return ParcelDecodeResult(
            args = results,
            consumed = parcel.dataPosition(),
            remaining = (totalSize - parcel.dataPosition()).coerceAtLeast(0),
            fullySuccessful = allOk
        )
    }

    /**
     * 计算 Parcel 中参数数据的起始偏移:
     *   12 byte strict-mode/work-source header + 4 byte nameLength
     *   + 2*(nameLength+1) byte UTF-16 chars(含 NUL)+ 4 byte 对齐
     */
    private fun computeArgStart(data: ByteArray): Int? {
        if (data.size < 16) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(HEADER_LEN)
        val nameLen = buffer.int
        if (nameLen <= 0 || nameLen > MAX_TOKEN_CHARS) return null
        val strBytes = (nameLen + 1) * 2
        val strPadded = (strBytes + 3) and 0x3.inv()
        val start = 16 + strPadded
        return if (start < data.size) start else null
    }

    private fun tryDecodeOne(parcel: Parcel, index: Int, type: String): DecodedArgument {
        return try {
            val value = decodeValue(parcel, type)
            DecodedArgument(
                index = index,
                declaredType = type,
                displayValue = truncate(value),
                status = DecodedArgument.Status.SUCCESS
            )
        } catch (t: Throwable) {
            DecodedArgument(
                index = index,
                declaredType = type,
                displayValue = "<解析失败>",
                status = DecodedArgument.Status.UNPARSED,
                errorMessage = t.message ?: t.javaClass.simpleName
            )
        }
    }

    /**
     * 按类型读一个值。类型名是 [MethodResolver.formatTypeName] 产出的短名
     * (`String`, `int[]`, `List<String>` 等)。
     *
     * 失败抛异常由 [tryDecodeOne] catch。
     */
    private fun decodeValue(parcel: Parcel, type: String): String {
        // 去掉泛型部分做 switch;再取最后一段成短名 —— 这样 fqn (`android.os.Bundle`)
        // 和短名(`Bundle`)都能命中同一个分支,降低跟 MethodResolver.formatTypeName 升级时的耦合。
        val outerType = type.substringBefore('<')
        val head = outerType.substringAfterLast('.')
        return when (head) {
            "int" -> parcel.readInt().toString()
            "long" -> parcel.readLong().toString()
            "boolean" -> (parcel.readInt() != 0).toString()
            "float" -> parcel.readFloat().toString()
            "double" -> parcel.readDouble().toString()
            "byte" -> parcel.readByte().toString()
            "short" -> parcel.readInt().toString() // Parcel 不存单字节 short
            "char" -> parcel.readInt().toInt().toChar().toString()

            "String" -> quote(parcel.readString())
            "CharSequence" -> {
                // android.text.TextUtils.CHAR_SEQUENCE_CREATOR 是读 CharSequence 的官方入口
                val cs = android.text.TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel)
                quote(cs?.toString())
            }

            "IBinder" -> {
                val b = parcel.readStrongBinder()
                if (b == null) "null" else "IBinder(${b.interfaceDescriptor ?: b::class.java.simpleName})"
            }

            "Bundle" -> {
                val b = parcel.readBundle()
                formatBundle(b)
            }
            "Intent" -> {
                // Intent 写入有个 "has value" int 前缀:null=0,非 null=1 + 完整数据
                val hasValue = parcel.readInt()
                if (hasValue == 0) "null"
                else Intent.CREATOR.createFromParcel(parcel).toUri(0)
            }
            "Uri" -> {
                val u: Uri? = Uri.CREATOR.createFromParcel(parcel)
                u?.toString() ?: "null"
            }
            "ComponentName" -> {
                val hasValue = parcel.readInt()
                if (hasValue == 0) "null"
                else ComponentName.readFromParcel(parcel)?.flattenToShortString() ?: "null"
            }

            "int[]" -> parcel.createIntArray()?.joinToString(prefix = "[", postfix = "]") ?: "null"
            "long[]" -> parcel.createLongArray()?.joinToString(prefix = "[", postfix = "]") ?: "null"
            "String[]" -> parcel.createStringArray()
                ?.joinToString(prefix = "[", postfix = "]") { quote(it) } ?: "null"
            "byte[]" -> parcel.createByteArray()?.let { "bytes[${it.size}]" } ?: "null"

            "Map", "HashMap" -> decodeStringMap(parcel, type)

            "List" -> {
                // 根据泛型内层类型区分:AIDL 中只有 List<String>、List<IBinder>、List<Parcelable> 三种常见
                val inner = type.substringAfter('<', "").substringBefore('>').trim()
                val innerHead = inner.substringAfterLast('.')
                when (innerHead) {
                    "String" ->
                        parcel.createStringArrayList()?.joinToString(prefix = "[", postfix = "]") { quote(it) } ?: "null"
                    "IBinder" ->
                        parcel.createBinderArrayList()?.joinToString(prefix = "[", postfix = "]") { it?.toString() ?: "null" } ?: "null"
                    else -> {
                        // List<Parcelable> 通用路径:白名单内的 framework Parcelable 用 createTypedArrayList(CREATOR)
                        val fqn = PARCELABLE_WHITELIST[innerHead] ?: PARCELABLE_WHITELIST[inner]
                            ?: throw UnsupportedOperationException("List<$inner> 暂未支持")
                        val creator = loadCreator(fqn)
                            ?: throw UnsupportedOperationException("$fqn CREATOR 不可用")
                        val list = parcel.createTypedArrayList(creator)
                        list?.joinToString(prefix = "[", postfix = "]") { it?.toString() ?: "null" } ?: "null"
                    }
                }
            }

            else -> {
                // framework Parcelable 白名单:声明类型在白名单内才走通用 CREATOR 反射;
                // 不在白名单一律抛 UnsupportedOperationException → tryDecodeOne 标 UNPARSED。
                val fqn = PARCELABLE_WHITELIST[head] ?: PARCELABLE_WHITELIST[outerType]
                if (fqn != null) {
                    decodeFrameworkParcelable(parcel, fqn)
                } else {
                    throw UnsupportedOperationException("未识别的类型: $type")
                }
            }
        }
    }

    /**
     * 解码 AIDL `Map<String, String>`。
     *
     * AOSP AIDL 编译器对 K/V 都是 String 的 Map 走快速路径 —— 不带 typed-value
     * marker,直接 `writeInt(size) + N × (writeString key, writeString value)`。
     * `IPackageManager.notifyDexLoad` 的 classLoaderContextMap 即此模式。
     *
     * 其它 Map<K, V>(任一不是 String)走 generic `writeValue`(每个 entry 的
     * key/value 前都有 4B type marker),本分支不处理 —— 直接抛 UnsupportedOp,
     * 由 [tryDecodeOne] 标 UNPARSED,详情页仍能看 hex 自查。
     */
    private fun decodeStringMap(parcel: Parcel, type: String): String {
        val inner = type.substringAfter('<', "").substringBefore('>')
        val parts = inner.split(',').map { it.trim().substringAfterLast('.') }
        if (parts.size != 2 || parts[0] != "String" || parts[1] != "String") {
            throw UnsupportedOperationException("Map<${parts.joinToString(",")}> 暂未支持")
        }
        val n = parcel.readInt()
        if (n == -1) return "null"
        // size 上限保护:避免在错误对齐的 Parcel 里读到一个巨大值进入死循环
        require(n in 0..10_000) { "Map size 不合理: $n" }
        val sb = StringBuilder("{")
        var shown = 0
        for (i in 0 until n) {
            val k = parcel.readString()
            val v = parcel.readString()
            if (shown < 8) {
                if (shown > 0) sb.append(", ")
                sb.append(quote(k)).append(":").append(quote(v))
                shown++
            }
        }
        if (n > shown) sb.append(", …(+${n - shown})")
        sb.append("}")
        return sb.toString()
    }

    /**
     * 反射加载 framework Parcelable 的 CREATOR 字段。结果缓存。
     *
     * 失败原因可能是:ClassNotFoundException(类名拼错或 SDK 版本不匹配)/
     * NoSuchFieldException(目标类没 CREATOR)/ IllegalAccessException(罕见,
     * framework 类基本都 public)。任一失败返回 null,由 caller 决定降级策略。
     */
    private fun loadCreator(fqn: String): Parcelable.Creator<*>? {
        creatorCache[fqn]?.let { return it }
        return try {
            val cls = Class.forName(fqn)
            val field = cls.getField("CREATOR")
            val creator = field.get(null) as? Parcelable.Creator<*>
            if (creator != null) creatorCache[fqn] = creator
            creator
        } catch (t: Throwable) {
            CLogUtils.v(TAG, "loadCreator($fqn) 失败: ${t.message}")
            null
        }
    }

    /**
     * 解一个 framework Parcelable。AIDL 默认走 "writeToParcel + null marker" 模式
     * (先写一个 int=0/1 表示是否 null,非 null 才有 Parcelable 字段),与现有
     * Intent / ComponentName 分支模式一致。
     */
    private fun decodeFrameworkParcelable(parcel: Parcel, fqn: String): String {
        val hasValue = parcel.readInt()
        if (hasValue == 0) return "null"
        val creator = loadCreator(fqn)
            ?: throw UnsupportedOperationException("$fqn CREATOR 不可用")
        val obj = creator.createFromParcel(parcel)
        return obj?.toString() ?: "null"
    }

    private fun formatBundle(b: Bundle?): String {
        if (b == null) return "null"
        val keys = try { b.keySet() } catch (t: Throwable) { return "Bundle(无法读取: ${t.message})" }
        if (keys.isEmpty()) return "Bundle{}"
        // 展开顶层 key:value,value 用 toString(只读,不反序列化子 Parcelable)
        val parts = keys.take(6).joinToString(", ") { k ->
            val v = try { b.get(k) } catch (_: Throwable) { "<错误>" }
            "$k=${shortValue(v)}"
        }
        val suffix = if (keys.size > 6) ", …(+${keys.size - 6})" else ""
        return "Bundle{$parts$suffix}"
    }

    private fun shortValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is ByteArray -> "bytes[${v.size}]"
        is IntArray -> v.joinToString(prefix = "[", postfix = "]")
        is LongArray -> v.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { shortValue(it) }
        else -> v.toString()
    }

    private fun quote(s: String?): String {
        if (s == null) return "null"
        // 换行符在一行显示里会破版,统一替成 ⏎
        return "\"" + s.replace("\n", "⏎").replace("\r", "") + "\""
    }

    private fun truncate(s: String): String =
        if (s.length <= DISPLAY_MAX_LEN) s else s.substring(0, DISPLAY_MAX_LEN) + "…"
}
