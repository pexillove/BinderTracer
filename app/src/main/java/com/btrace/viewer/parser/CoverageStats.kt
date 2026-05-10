package com.btrace.viewer.parser

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.decoders.DecodeSource
import java.util.concurrent.atomic.AtomicLong

/**
 * spec § 6.5 L4 覆盖率仪表盘的 5 类聚合维度。
 *
 * DecodeSource 8 档按以下规则归并:
 *   - AIDL    = AIDL_Q + AIDL_P + AIDL_O
 *   - HIDL    = HIDL_DESCRIPTOR
 *   - REPLY   = REPLY
 *   - SPECIAL = SPECIAL_CODE
 *   - UNKNOWN = TARGET_REF + RAW_ASCII(LOW 置信度兜底)
 *
 * vendor 维度本期不展示(L4-B 才做)。
 */
enum class CoverageBucket {
    AIDL, HIDL, REPLY, SPECIAL, UNKNOWN;

    companion object {
        fun of(source: DecodeSource): CoverageBucket = when (source) {
            DecodeSource.AIDL_Q, DecodeSource.AIDL_P, DecodeSource.AIDL_O -> AIDL
            DecodeSource.HIDL_DESCRIPTOR -> HIDL
            DecodeSource.REPLY -> REPLY
            DecodeSource.SPECIAL_CODE -> SPECIAL
            DecodeSource.TARGET_REF, DecodeSource.RAW_ASCII -> UNKNOWN
        }
    }
}

/**
 * UI 读取的不可变快照。compose 端直接 collectAsState。
 *
 * @param total       已观测到的事件总数(扣除 evict 的部分,等于当前缓冲区里的事件数)
 * @param resolved    interfaceName 已解析(decodeSource != null 且 != Unknown 桶)的数量
 * @param buckets     5 类计数
 */
data class CoverageSnapshot(
    val total: Long,
    val resolved: Long,
    val buckets: Map<CoverageBucket, Long>
) {
    /** 解析率,total == 0 时返回 0.0 而不是 NaN。 */
    val resolveRate: Double
        get() = if (total <= 0L) 0.0 else resolved.toDouble() / total.toDouble()

    companion object {
        val EMPTY = CoverageSnapshot(
            total = 0L,
            resolved = 0L,
            buckets = CoverageBucket.values().associateWith { 0L }
        )
    }
}

/**
 * spec § 6.5 覆盖率统计器。EventRepository 单例持有,在 add/evict 双向增量更新。
 *
 * O(1) per add/evict;线程安全靠 AtomicLong 数组,读 [snapshot] 是非锁原子读,
 * 不阻塞热路径。snapshot 多个字段间不强制原子(读到 total=N+1 而 buckets 仍是 N
 * 的瞬态情况存在,但下一帧 tick 立刻自洽,UI 不会观察到长期偏差)。
 */
class CoverageStats {

    private val total = AtomicLong(0L)
    private val resolved = AtomicLong(0L)
    private val bucketCounts: Map<CoverageBucket, AtomicLong> =
        CoverageBucket.values().associateWith { AtomicLong(0L) }

    fun onEventAdded(event: BinderEvent) {
        total.incrementAndGet()
        val bucket = bucketOf(event)
        bucketCounts.getValue(bucket).incrementAndGet()
        if (isResolved(event, bucket)) resolved.incrementAndGet()
    }

    fun onEventEvicted(event: BinderEvent) {
        total.decrementAndGet()
        val bucket = bucketOf(event)
        bucketCounts.getValue(bucket).decrementAndGet()
        if (isResolved(event, bucket)) resolved.decrementAndGet()
    }

    fun reset() {
        total.set(0L)
        resolved.set(0L)
        bucketCounts.values.forEach { it.set(0L) }
    }

    fun snapshot(): CoverageSnapshot {
        val buckets = bucketCounts.mapValues { it.value.get() }
        return CoverageSnapshot(
            total = total.get(),
            resolved = resolved.get(),
            buckets = buckets
        )
    }

    private fun bucketOf(event: BinderEvent): CoverageBucket =
        event.decodeSource?.let { CoverageBucket.of(it) } ?: CoverageBucket.UNKNOWN

    /**
     * "已解析"的判定:必须有 decodeSource,且不是 Unknown 桶,且 interfaceName 不是兜底字面量
     * "Unknown"(BinderEvent.interfaceName getter 在 _interfaceName == null 时返回该字面量)。
     */
    private fun isResolved(event: BinderEvent, bucket: CoverageBucket): Boolean {
        if (event.decodeSource == null) return false
        if (bucket == CoverageBucket.UNKNOWN) return false
        return event.interfaceName != "Unknown"
    }
}
