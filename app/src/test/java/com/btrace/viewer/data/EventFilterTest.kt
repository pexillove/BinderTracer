package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.StackFrame
import com.btrace.viewer.model.StackQuality
import com.btrace.viewer.model.StackTrace
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.decoders.DecodeSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec § 6.5:类型 chip 的桶白名单语义。
 *
 * - 默认 ALL_BUCKETS:isEmpty == true,所有事件通过(老调用方仅传 interface/method 时不变更行为)。
 * - bucketsAllowed = 子集时,事件按 8→5 桶聚合后必须命中集合才通过(case A)。
 * - 与 interface/method 子串过滤组合:三者 AND(case B)。
 */
class EventFilterTest {

    private fun event(
        id: Long,
        source: DecodeSource?,
        iface: String = "android.app.IFoo",
        method: String = "doIt"
    ): BinderEvent {
        // copy() 是浅拷贝,懒加载缓存不会带过去 → interface/method 必须 set 在 copy 之后,
        // 与 CoverageStatsTest 同套路。
        val base = BinderEvent.createMock(
            interfaceName = "placeholder",
            methodName = "placeholder",
            callerPackage = "test",
            uid = 100
        ).copy(id = id)
        base.interfaceName = iface
        base.methodName = method
        base.decodeSource = source
        return base
    }

    @Test
    fun `default filter is empty and matches all events`() {
        val filter = EventFilter()
        assertTrue(filter.isEmpty())
        assertTrue(filter.matches(event(1, DecodeSource.AIDL_Q)))
        assertTrue(filter.matches(event(2, DecodeSource.HIDL_DESCRIPTOR)))
        assertTrue(filter.matches(event(3, source = null)))
    }

    @Test
    fun `case A — bucketsAllowed AIDL+REPLY filters out HIDL`() {
        val filter = EventFilter(
            bucketsAllowed = setOf(CoverageBucket.AIDL, CoverageBucket.REPLY)
        )
        // AIDL_Q / AIDL_P / AIDL_O 都聚合到 AIDL → 通过
        assertTrue(filter.matches(event(1, DecodeSource.AIDL_Q)))
        assertTrue(filter.matches(event(2, DecodeSource.AIDL_O)))
        // REPLY → 通过
        assertTrue(filter.matches(event(3, DecodeSource.REPLY)))
        // HIDL → 拦截
        assertFalse(filter.matches(event(4, DecodeSource.HIDL_DESCRIPTOR)))
        // SPECIAL_CODE 不在白名单 → 拦截
        assertFalse(filter.matches(event(5, DecodeSource.SPECIAL_CODE)))
        // TARGET_REF / RAW_ASCII / null 都聚合到 UNKNOWN → 拦截
        assertFalse(filter.matches(event(6, DecodeSource.TARGET_REF)))
        assertFalse(filter.matches(event(7, source = null)))
    }

    @Test
    fun `case B — full buckets selected with interface text filter combines as AND`() {
        // 桶全选 = 桶维度不过滤,只看 interface 子串。
        val filter = EventFilter(
            interfaceContains = "ActivityManager",
            bucketsAllowed = EventFilter.ALL_BUCKETS
        )
        // 接口包含 ActivityManager + AIDL → 通过
        assertTrue(filter.matches(
            event(1, DecodeSource.AIDL_Q, iface = "android.app.IActivityManager")
        ))
        // 接口包含 ActivityManager + HIDL(桶虽全选,接口仍要命中)→ 通过
        assertTrue(filter.matches(
            event(2, DecodeSource.HIDL_DESCRIPTOR, iface = "android.app.IActivityManager\$Sub")
        ))
        // 接口不含 ActivityManager → 即使 AIDL 也拦截(三者 AND)
        assertFalse(filter.matches(
            event(3, DecodeSource.AIDL_Q, iface = "android.os.IServiceManager")
        ))
    }

    @Test
    fun `bucketsAllowed subset combined with interface filter — both must match`() {
        // 桶限定 [AIDL] + 接口过滤 ActivityManager:HIDL 即使接口命中也被拦掉。
        val filter = EventFilter(
            interfaceContains = "ActivityManager",
            bucketsAllowed = setOf(CoverageBucket.AIDL)
        )
        assertTrue(filter.matches(
            event(1, DecodeSource.AIDL_Q, iface = "android.app.IActivityManager")
        ))
        assertFalse(filter.matches(
            event(2, DecodeSource.HIDL_DESCRIPTOR, iface = "android.app.IActivityManager")
        ))
        assertFalse(filter.matches(
            event(3, DecodeSource.AIDL_Q, iface = "android.os.IServiceManager")
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stackModuleContains:任一栈帧 module 子串命中即通过;无栈则 fail-closed
    // ─────────────────────────────────────────────────────────────────────────

    private fun frame(module: String) = StackFrame(pc = 0L, module = module, symbol = "", offset = 0L)

    private fun stackOf(vararg uModules: String): StackTrace = StackTrace(
        quality = StackQuality.FULL,
        truncated = 0,
        kFrames = emptyList(),
        uFrames = uModules.map { frame(it) }
    )

    private fun eventWithStack(id: Long, stack: StackTrace?): BinderEvent =
        event(id, DecodeSource.AIDL_Q).copy(stackTrace = stack).also {
            it.interfaceName = "android.app.IFoo"
            it.methodName = "doIt"
            it.decodeSource = DecodeSource.AIDL_Q
        }

    @Test
    fun `stackModuleContains matches when any uFrame module contains substring`() {
        val filter = EventFilter(stackModuleContains = "libgui")
        val hit = eventWithStack(
            1,
            stackOf("/system/lib64/libc.so", "/system/lib64/libgui.so", "/system/lib64/libbinder.so")
        )
        assertTrue(filter.matches(hit))
    }

    @Test
    fun `stackModuleContains rejects events whose stack misses the substring`() {
        val filter = EventFilter(stackModuleContains = "libgui")
        val miss = eventWithStack(
            1,
            stackOf("/system/lib64/libc.so", "/system/lib64/libbinder.so")
        )
        assertFalse(filter.matches(miss))
    }

    @Test
    fun `stackModuleContains is case-insensitive`() {
        val filter = EventFilter(stackModuleContains = "LIBGUI")
        val hit = eventWithStack(1, stackOf("/system/lib64/libgui.so"))
        assertTrue(filter.matches(hit))
    }

    @Test
    fun `stackModuleContains rejects events without stack (fail-closed)`() {
        val filter = EventFilter(stackModuleContains = "libgui")
        val noStack = eventWithStack(1, stack = null)
        assertFalse(filter.matches(noStack))
    }

    @Test
    fun `stackModuleContains blank does not affect events without stack`() {
        // 该字段为空 = 不参与过滤,无栈事件不受影响。
        val filter = EventFilter()
        assertTrue(filter.matches(eventWithStack(1, stack = null)))
    }
}
