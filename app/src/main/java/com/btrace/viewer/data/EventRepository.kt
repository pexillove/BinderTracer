package com.btrace.viewer.data

import androidx.annotation.VisibleForTesting
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.Direction
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.CoverageSnapshot
import com.btrace.viewer.parser.CoverageStats
import com.btrace.viewer.parser.InterfaceIndex
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.ParcelArgumentDecoder
import com.btrace.viewer.parser.ParcelParser
import com.btrace.viewer.parser.decoders.DecodeSource
import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 事件过滤条件。
 *
 * - [interfaceContains] / [methodContains]:子串匹配,空串表示该维度不参与。
 * - [bucketsAllowed]:5 桶白名单,事件按 [CoverageBucket.of] 聚合后落在集合内才通过;
 *   默认全 5 桶 = 不过滤。空集合等同于全选(避免 UI 异常清空导致零事件可见)。
 */
data class EventFilter(
    val interfaceContains: String = "",
    val methodContains: String = "",
    val bucketsAllowed: Set<CoverageBucket> = ALL_BUCKETS,
    /**
     * 调用栈中出现过的 .so 路径子串(忽略大小写)。空串 = 该维度不参与。
     *
     * 典型用法:输入 `libgui` 看哪些 binder 调用经过了 libgui.so。
     *
     * 语义:对事件的 stackTrace.uFrames + kFrames 的 module 字段做子串匹配,任一帧命中即通过。
     * **该字段非空时**,stackTrace==null 的事件一律不通过(没栈无法验证)。
     */
    val stackModuleContains: String = ""
) {
    fun isEmpty(): Boolean =
        interfaceContains.isBlank() &&
            methodContains.isBlank() &&
            stackModuleContains.isBlank() &&
            (bucketsAllowed.isEmpty() || bucketsAllowed.size == ALL_BUCKETS.size)

    fun matches(event: BinderEvent): Boolean {
        if (isEmpty()) return true
        val interfaceMatch = interfaceContains.isBlank() ||
            event.interfaceName.contains(interfaceContains, ignoreCase = true)
        val methodMatch = methodContains.isBlank() ||
            event.methodName.contains(methodContains, ignoreCase = true)
        val bucketMatch = bucketsAllowed.isEmpty() ||
            bucketsAllowed.size == ALL_BUCKETS.size ||
            bucketsAllowed.contains(bucketOf(event))
        val stackModuleMatch = stackModuleContains.isBlank() || run {
            val st = event.stackTrace ?: return@run false
            st.uFrames.any { it.module.contains(stackModuleContains, ignoreCase = true) } ||
                st.kFrames.any { it.module.contains(stackModuleContains, ignoreCase = true) }
        }
        return interfaceMatch && methodMatch && bucketMatch && stackModuleMatch
    }

    companion object {
        val ALL_BUCKETS: Set<CoverageBucket> = CoverageBucket.values().toSet()

        fun bucketOf(event: BinderEvent): CoverageBucket =
            event.decodeSource?.let { CoverageBucket.of(it) } ?: CoverageBucket.UNKNOWN
    }
}

/**
 * 事件数据仓库 —— 管理 Binder 事件的缓存、FIFO 淘汰与过滤快照分发。
 *
 * 容量(`maxEvents`)从 Phase 0 起改为运行时可配置:由 [SettingsRepository] 读出后
 * 通过 [setMaxEvents] 注入,默认 [DEFAULT_MAX_EVENTS] = 5000。设置项变小会立即裁剪缓存。
 *
 * 性能特性:
 *   - FIFO 缓冲与 id 索引由 [BoundedEventBuffer] 统一封装,add/findById 均 O(1)。
 *   - UI 订阅侧走 tick 合并:写入只置 [dirty],一个后台协程以 [EMIT_INTERVAL_MS]
 *     的周期吞掉中间态,合并发射一次快照。1000+ events/s 下 Compose 重组频率被
 *     钳在 ≤25Hz。
 *   - 热路径(addEvent/parseEvent)无 verbose 日志,避免字符串模板分配。
 */
@Singleton
class EventRepository @Inject constructor(
    private val parcelParser: ParcelParser,
    private val methodResolver: MethodResolver,
    private val argumentDecoder: ParcelArgumentDecoder,
    private val appRepository: AppRepository,
    private val interfaceIndex: InterfaceIndex,
    private val serviceManagerCatalog: com.btrace.viewer.parser.ServiceManagerCatalog,
) {
    companion object {
        private const val TAG = "EventRepository"
        const val DEFAULT_MAX_EVENTS = 5000
        const val MIN_MAX_EVENTS = 500
        const val MAX_MAX_EVENTS = 50_000

        private const val EMIT_INTERVAL_MS = 40L
    }

    // lock 仅保护 rate 计数器、_eventCount、setMaxEvents 的同步;
    // FIFO 队列与 id 索引由 buffer 自身负责。
    private val lock = Any()

    // spec § 6.5:覆盖率统计,与 buffer 同生命周期。buffer 触发淘汰时回调进来递减,
    // addEvent 路径直接增量,clearEvents 跟着归零。
    // 必须早于 buffer 声明 —— buffer 的 .also { onEvicted = ... } 闭包捕获 coverageStats,
    // 顺序反过来 Kotlin 编译期不会报错但运行期会 NPE。
    private val coverageStats = CoverageStats()

    // 容量受限的 FIFO 缓冲(deque + id 索引 + 截断),线程安全由内部 synchronized 提供。
    private val buffer = BoundedEventBuffer(DEFAULT_MAX_EVENTS).also {
        // buffer 在 add/setCapacity/clear 时回调被淘汰的事件,这里直接喂给 CoverageStats。
        // 回调发生在 lock 内部(addEvent / setMaxEvents / clearEvents 都已 synchronized),
        // CoverageStats 自身用 AtomicLong,不会再次加锁。
        it.onEvicted = { evicted -> coverageStats.onEventEvicted(evicted) }
    }

    private val _coverage = MutableStateFlow(CoverageSnapshot.EMPTY)
    val coverageFlow: StateFlow<CoverageSnapshot> = _coverage.asStateFlow()

    private val _filteredEvents = MutableStateFlow<List<BinderEvent>>(emptyList())
    val filteredEvents: StateFlow<List<BinderEvent>> = _filteredEvents.asStateFlow()

    private val _currentFilter = MutableStateFlow(EventFilter())
    val currentFilter: StateFlow<EventFilter> = _currentFilter.asStateFlow()

    private val _eventCount = MutableStateFlow(0)
    val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

    private var lastSecondCount = 0
    private var lastSecondTime = System.currentTimeMillis()
    private val _eventRate = MutableStateFlow(0)
    val eventRate: StateFlow<Int> = _eventRate.asStateFlow()

    // 写端置位,tick 协程吞掉并发射一次快照。
    private val dirty = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // spec § 6.5:当前监控目标的 uid,由 MonitorViewModel.startMonitoring 灌入。
    // <= 0 时 Direction.infer 返回 UNKNOWN,UI 退化为不分方向。
    @Volatile
    private var targetUid: Int = 0

    // spec 2026-05-03 § 3.2:被监控 App 的包名(由 setTargetUid 反查并 cache)。
    // parseEvent 计算 resolveCandidates 时优先级 1。null = 未启动监控 / 反查失败。
    @Volatile
    private var targetPackage: String? = null

    init {
        CLogUtils.d(TAG, "EventRepository 初始化, capacity=${buffer.capacity()}, emit tick=${EMIT_INTERVAL_MS}ms")
        scope.launch {
            while (isActive) {
                delay(EMIT_INTERVAL_MS)
                if (dirty.compareAndSet(true, false)) {
                    emitSnapshot()
                }
            }
        }
    }

    /**
     * MonitorViewModel.startMonitoring 时灌入当前监控目标 uid,用于 [parseEvent] 推断 direction。
     * 不传或 uid <= 0 时所有事件 direction 为 UNKNOWN(UI 退化为不分方向)。
     */
    fun setTargetUid(uid: Int) {
        targetUid = uid
        // spec 2026-05-03 § 3.2:在 setTargetUid 时反查包名一次性 cache,避免每事件都查 PM。
        // uid <= 0 → 清空(MonitorViewModel.stopMonitoring 时灌 0)。
        targetPackage = if (uid > 0) appRepository.getPackageNameForUid(uid) else null
        // 同步给 ParcelParser → TransactionPairer:配对方向判定要用同一个 targetUid。
        // EventRepository 与 ParcelParser 都 @Singleton,这里只在切换监控目标时被调一次。
        parcelParser.setCurrentTargetUid(uid)
        CLogUtils.d(TAG, "setTargetUid() targetUid=$uid targetPackage=$targetPackage")
    }

    /**
     * 调整缓存上限。新值 < 当前 size 时立即裁剪最老事件。
     *
     * 取值范围 [[MIN_MAX_EVENTS], [MAX_MAX_EVENTS]] —— 太小没意义(5 秒就刷光了),
     * 太大会占几十 MB 堆。越界值会被 clamp 到边界,而非抛异常,避免 UI 滑块边缘
     * 抖动崩主流程。
     */
    fun setMaxEvents(value: Int) {
        val clamped = value.coerceIn(MIN_MAX_EVENTS, MAX_MAX_EVENTS)
        synchronized(lock) {
            if (clamped == buffer.capacity()) return
            CLogUtils.i(TAG, "setMaxEvents() ${buffer.capacity()} -> $clamped")
            buffer.setCapacity(clamped)
            _eventCount.value = buffer.size()
        }
        dirty.set(true)
    }

    fun getMaxEvents(): Int = synchronized(lock) { buffer.capacity() }

    /**
     * 添加新事件。调用方保证单协程串行(当前为 MonitorViewModel 的 collect 协程)。
     *
     * 解析(含反射、包名查询)放在锁外,与 emit 协程构建快照可并行 —— 解析只写
     * event 自身的懒加载字段,不碰共享容器。
     */
    fun addEvent(event: BinderEvent) {
        parseEvent(event)

        // BoundedEventBuffer 不是线程安全的(纯逻辑 / JVM 测试无依赖),
        // 必须由调用方串行化。这里和 setMaxEvents/getMaxEvents/emitSnapshot/
        // clearEvents/getEvent 共用同一把 lock。
        synchronized(lock) {
            // CoverageStats 增量发生在 buffer.add 之前,这样 buffer 满时
            // onEvicted 回调里的 onEventEvicted 才能找到对应的 +1,净结果为 0。
            coverageStats.onEventAdded(event)
            buffer.add(event)
            _eventCount.value = buffer.size()
            val now = System.currentTimeMillis()
            lastSecondCount++
            if (now - lastSecondTime >= 1000) {
                _eventRate.value = lastSecondCount
                lastSecondCount = 0
                lastSecondTime = now
            }
        }

        dirty.set(true)
    }

    /**
     * 流程:先反查 callerPackage(用于后续把目标 App 自己的 ClassLoader 喂给 MethodResolver),
     * 再按当前 targetUid 推断 direction(spec § 6.5),
     * 然后统一走 [ParcelParser.decodePipeline] 8 档探测器(spec § 6.3.1),
     * 按命中的 [DecodeSource] 分流:
     *   - REPLY/SPECIAL/HIDL/RAW_ASCII/TARGET_REF:无 AIDL 参数语义,只用 methodHint
     *   - AIDL_Q/P/O:走 MethodResolver + ParcelArgumentDecoder 解参数值
     *
     * 入向调用(INCOMING_REQUEST)的 callerPackage 仍按 sender uid 反查 ——
     * 此时 sender 是外部调用方,callerPackage 表达的就是"是谁在调我",符合 UI 语义。
     */
    private fun parseEvent(event: BinderEvent) {
        val senderPkg = appRepository.getPackageNameForUid(event.uid)
        if (senderPkg != null) {
            event.callerPackage = senderPkg
        }
        // request 帧带 toUid 时,反查目标进程的可读名(系统 uid 走助记名,app uid 走包名)。
        // reply 帧 toUid 通常不带(server→client 方向),反查跳过避免误标。
        if (!event.isReply && event.toUid != 0) {
            event.toPackage = appRepository.getPackageOrSystemNameForUid(event.toUid)
        }

        event.direction = Direction.infer(
            senderUid = event.uid,
            toUid = event.toUid,
            isReply = event.isReply,
            targetUid = targetUid
        )

        val result = parcelParser.decodePipeline(event)
        event.interfaceName = result.interfaceName
        event.decodeSource = result.source
        event.confidence = result.confidence

        // spec 2026-05-03 § 3.2:候选包优先级 = targetPackage → InterfaceIndex.lookupOrdered → senderPkg。
        // 顺序去重(LinkedHashSet),空字符串 / null 跳过。
        event.resolveCandidates = computeResolveCandidates(senderPkg, result.interfaceName)

        when (result.source) {
            DecodeSource.REPLY,
            DecodeSource.SPECIAL_CODE,
            DecodeSource.HIDL_DESCRIPTOR,
            DecodeSource.RAW_ASCII,
            DecodeSource.TARGET_REF -> {
                // 这些档要么已经给了 methodHint(REPLY/SPECIAL/TARGET_REF),
                // 要么没有 AIDL 参数语义。methodHint 不为空就用,否则用 "code=N" 兜底。
                event.methodName = result.methodHint ?: "code=${event.code}"
                // TARGET_REF 兜底升级:用 ServiceManagerCatalog 的全 service 反射表按 code +
                // toPackage 反查候选 service / method。命中则把 interfaceName 换成
                // descriptor,methodName 换成反射出的真实名;miss 保持 raw target@0xN。
                if (result.source == DecodeSource.TARGET_REF) {
                    val candidate = serviceManagerCatalog.lookupByCode(event.code, event.toPackage)
                    if (candidate != null) {
                        event.interfaceName = candidate.descriptor
                        event.methodName = candidate.methodName
                    }
                }
            }
            DecodeSource.AIDL_Q,
            DecodeSource.AIDL_P,
            DecodeSource.AIDL_O -> {
                // AIDL 路径:走 MethodResolver 拿方法签名 + ParcelArgumentDecoder 解参数。
                // spec § 9.2:返回 null = 连方法名都没解到,走启发式兜底 + "code=N" 显示。
                // spec 2026-05-03 § 3.3:入参从单 packageName 改为 candidates 列表。
                val signature = methodResolver.getMethodSignature(
                    result.interfaceName,
                    event.code,
                    event.resolveCandidates,
                )
                if (signature == null) {
                    val sniffed = parcelParser.sniffArgumentTypes(event.rawParcel)
                    event.sniffedSignature = sniffed
                    val code = "code=${event.code}"
                    event.methodName = if (sniffed.isEmpty()) code else "$code (${sniffed.joinToString(", ")})"
                    return
                }
                event.methodName = signature.methodName
                // spec § 9.3:按 paramTypes 顺序解参数值。零参方法不调,免白白 Parcel.obtain。
                if (signature.paramTypes.isNotEmpty()) {
                    val decResult = argumentDecoder.decode(event.rawParcel, signature.paramTypes)
                    event.parsedArgs = decResult.args
                }
            }
        }
    }

    /**
     * 单元测试钩子:跳过 [parseEvent] 直接放入 buffer,用于 [findReplyForRequest] 等
     * 不依赖解析路径的逻辑测试。生产路径不要用 —— 跳过 parseEvent 后事件没有
     * interfaceName / methodName / direction / decodeSource,会污染 CoverageStats
     * 与 UI 显示。@VisibleForTesting(NONE) 让 Lint 在生产代码引用时报错。
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun addEventDirectForTest(event: BinderEvent) {
        synchronized(lock) {
            coverageStats.onEventAdded(event)
            buffer.add(event)
            _eventCount.value = buffer.size()
        }
    }

    /**
     * 单元测试钩子:直接调用 [computeResolveCandidates] 内部逻辑,可临时覆盖 targetPackage。
     * 不影响实例真实状态。
     */
    internal fun computeResolveCandidatesForTest(
        senderPkg: String?,
        interfaceName: String?,
        targetPackageOverride: String? = null,
    ): List<String> {
        val origin = targetPackage
        if (targetPackageOverride != null) targetPackage = targetPackageOverride
        try {
            return computeResolveCandidates(senderPkg, interfaceName)
        } finally {
            if (targetPackageOverride != null) targetPackage = origin
        }
    }

    /**
     * spec 2026-05-03 § 3.2:计算候选包列表(顺序去重,空跳过)。
     *
     * 优先级:
     *   1. [targetPackage](被监控 App)
     *   2. [InterfaceIndex.lookupOrdered](按首次插入顺序的索引命中)
     *   3. [senderPkg](sender uid 反查包名)
     *
     * 返回不可变 list。三档都空 → emptyList。
     */
    private fun computeResolveCandidates(senderPkg: String?, interfaceName: String?): List<String> {
        val out = LinkedHashSet<String>()
        targetPackage?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        if (!interfaceName.isNullOrEmpty()) {
            for (p in interfaceIndex.lookupOrdered(interfaceName)) {
                if (p.isNotEmpty()) out.add(p)
            }
        }
        senderPkg?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out.toList()
    }

    /**
     * 设置过滤条件。过滤器变化需要立即反映,不等下一个 tick。
     */
    fun setFilter(filter: EventFilter) {
        CLogUtils.d(TAG, "setFilter() interface='${filter.interfaceContains}', method='${filter.methodContains}'")
        _currentFilter.value = filter
        scope.launch { emitSnapshot() }
    }

    fun clearFilter() {
        CLogUtils.d(TAG, "clearFilter() 清除过滤条件")
        setFilter(EventFilter())
    }

    /**
     * 构建并发射一次快照。buffer.snapshot() 自身已加锁返回不可变 list,
     * 过滤与 StateFlow 写入完全在锁外,与生产者热路径解耦。
     */
    private fun emitSnapshot() {
        val filter = _currentFilter.value
        val all = synchronized(lock) { buffer.snapshot() }
        // UI 列表层默认隐藏「matched reply」(已配对到 request 的 reply 帧)。
        // 这类 reply 信息已经通过 LinkedReplySection 在 request 详情页完整呈现,
        // 列表里再单独展示一条 reply item 视觉冗余。
        // 保留:isReply 但 pairId == 0(orphan reply,daemon 没解出 pair),仍单独展示
        // 让用户感知"有 reply 但配对失败"。
        // 注意:matched reply 在 buffer 里保留不删,linkedReply 反查依赖 buffer 全量。
        val visible = all.filter { !it.isReply || it.pairId == 0L }
        val snapshot = if (filter.isEmpty()) visible else visible.filter { filter.matches(it) }
        _filteredEvents.value = snapshot
        // CoverageStats 自身字段读取无锁(AtomicLong),与 emitSnapshot 节流到 25Hz
        // 一起,UI 端覆盖率卡片每 40ms 收一次新值,不抖也不卡。
        _coverage.value = coverageStats.snapshot()
    }

    /**
     * 清空所有事件
     */
    fun clearEvents() {
        CLogUtils.i(TAG, "clearEvents() 清空所有事件")
        synchronized(lock) {
            // buffer.clear() 会对每个事件触发 onEvicted → coverageStats.onEventEvicted,
            // 自然把所有桶递减到 0。reset() 是冗余兜底,挡住 onEvicted 回调遗漏的情况。
            buffer.clear()
            coverageStats.reset()
            _eventCount.value = 0
            _eventRate.value = 0
            lastSecondCount = 0
            lastSecondTime = System.currentTimeMillis()
        }
        _filteredEvents.value = emptyList()
        _coverage.value = CoverageSnapshot.EMPTY
        dirty.set(false)
    }

    /**
     * 获取事件详情 —— O(1) 查找。
     */
    fun getEvent(eventId: Long): BinderEvent? = synchronized(lock) { buffer.findById(eventId) }

    /**
     * 反查与 [request] 配对的 reply 事件。
     *
     * 配对规则(spec § 6.3.1 + spec 2026-05-09 § 3):
     *   - request.isReply == true → 直接返回 null(reply 不再有 reply)
     *   - request.pairId == 0L    → daemon 端无配对 ID(早期内核 / 协议),返回 null
     *   - 否则在内存事件 buffer 里扫一次,匹配第一条 isReply == true 且 pairId 相等的事件。
     *     合法 binder 流里同一 pairId 至多 1 条 reply;极小概率 daemon 上报重复时,
     *     按 deque FIFO 顺序取先入队的那条以保证幂等。
     *
     * 扫描走 [BoundedEventBuffer.findFirst] 锁内遍历,不构造 List 副本 ——
     * 容量上限 50 000 时也 < 1ms,与 emitSnapshot 同模式不破坏并发不变量。
     *
     * **边界**:reply 已到但因 buffer FIFO 容量被淘汰时,本方法返回 null,UI 退化
     * 到「等待 reply…」分支(LinkedReplySection 的文案已覆盖该可能性)。
     */
    fun findReplyForRequest(request: BinderEvent): BinderEvent? {
        if (request.isReply) return null
        if (request.pairId == 0L) return null
        val targetPairId = request.pairId
        return synchronized(lock) {
            buffer.findFirst { it.isReply && it.pairId == targetPairId }
        }
    }

    /**
     * 反向查找:reply 的 pairId → 配对 request,与 findReplyForRequest 对称。
     * reply 详情页用,填充「请求数据」section。reply 帧的 pairId 由 BPF 端在 reply 路径
     * 直接写入(spec 2026-05-09 daemon-reply-uid-filter),与 request 的 pairId 同源。
     *
     * **边界**:request 已被 buffer FIFO 容量淘汰(reply 还在)时返回 null,UI 退化到
     * 「配对 request 已被淘汰」分支(orphan reply 同等)。
     */
    fun findRequestForReply(reply: BinderEvent): BinderEvent? {
        if (!reply.isReply) return null
        if (reply.pairId == 0L) return null
        val targetPairId = reply.pairId
        return synchronized(lock) {
            buffer.findFirst { !it.isReply && it.pairId == targetPairId }
        }
    }

    /**
     * 添加模拟事件（用于UI测试）
     */
    fun addMockEvents() {
        CLogUtils.i(TAG, "addMockEvents() 添加模拟事件")
        val mockEvents = listOf(
            BinderEvent.createMock("android.app.IActivityManager", "startActivity", "com.example.app", 10086),
            BinderEvent.createMock("android.content.pm.IPackageManager", "getPackageInfo", "com.example.app", 10086),
            BinderEvent.createMock("android.app.IActivityManager", "broadcastIntent", "com.example.app", 10086),
            BinderEvent.createMock("android.os.IServiceManager", "getService", "com.example.app", 10086),
            BinderEvent.createMock("android.app.INotificationManager", "enqueueNotificationWithTag", "com.example.app", 10086)
        )
        mockEvents.forEach { addEvent(it) }
    }
}
