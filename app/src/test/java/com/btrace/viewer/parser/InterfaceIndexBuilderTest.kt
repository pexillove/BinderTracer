package com.btrace.viewer.parser

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * spec § 6.4 第 1 档 builder 的"挑 \$Stub 类名"纯函数单测,以及增量 merge 的幂等性单测。
 *
 * 完整的 dex 扫描路径(PackageManager / DexFile / 反射 DESCRIPTOR)依赖 Android runtime,
 * 不在纯 JVM 测试覆盖范围内 —— 留给 instrumented test。本文件覆盖:
 *   - [extractStubInterfaces]:类名筛选纯函数
 *   - [mergeStubsIntoIndex]:把扫描结果幂等并入 [InterfaceIndex] 的纯函数(scanPackageAsync 的核心)
 */
class InterfaceIndexBuilderTest {

    private lateinit var index: InterfaceIndex

    @Before
    fun setUp() {
        // Context 在内存表的 addEntry/lookup 路径里完全不会被触碰,mock 一个占位即可。
        index = InterfaceIndex(mock<Context>())
    }

    @Test
    fun `extractStubInterfaces picks classes ending with dollar Stub`() {
        val classNames = listOf(
            "com.foo.IFoo\$Stub",
            "com.bar.IBar\$Stub",
            "com.baz.SomeRandomClass",
            "android.app.IActivityManager\$Stub"
        )
        val result = extractStubInterfaces(classNames)
        assertEquals(3, result.size)
        assertTrue(result.contains("com.foo.IFoo\$Stub"))
        assertTrue(result.contains("com.bar.IBar\$Stub"))
        assertTrue(result.contains("android.app.IActivityManager\$Stub"))
    }

    @Test
    fun `extractStubInterfaces excludes nested Proxy and other inner classes`() {
        val classNames = listOf(
            "com.foo.IFoo\$Stub",
            "com.foo.IFoo\$Stub\$Proxy",        // AIDL 生成的 Proxy 嵌套类,不算 Stub 接口
            "com.foo.IFoo\$Stub\$NestedHelper",  // 任何 $Stub$XXX 嵌套类都不算
            "com.foo.NotAStubAtAll"
        )
        val result = extractStubInterfaces(classNames)
        // 只保留正好以 $Stub 结尾的那一条
        assertEquals(listOf("com.foo.IFoo\$Stub"), result)
    }

    @Test
    fun `extractStubInterfaces returns empty list when no candidates`() {
        val classNames = listOf("com.foo.A", "com.foo.B", "com.foo.IFoo")
        assertTrue(extractStubInterfaces(classNames).isEmpty())
    }

    @Test
    fun `extractStubInterfaces handles empty input`() {
        assertTrue(extractStubInterfaces(emptyList()).isEmpty())
    }

    // ---- mergeStubsIntoIndex (scanPackageAsync 核心) ---------------------------------------

    @Test
    fun `mergeStubsIntoIndex adds entries for the package`() {
        val descriptors = listOf("com.foo.IFoo", "com.foo.IBar")
        mergeStubsIntoIndex(index, "com.foo.app", descriptors)
        assertEquals(setOf("com.foo.app"), index.lookup("com.foo.IFoo"))
        assertEquals(setOf("com.foo.app"), index.lookup("com.foo.IBar"))
        assertEquals(2, index.size())
    }

    @Test
    fun `mergeStubsIntoIndex same package multiple times is idempotent`() {
        // review P1 核心需求:scanPackageAsync 同一包多次调用,index 状态稳定。
        val descriptors = listOf("com.foo.IFoo", "com.foo.IBar")
        mergeStubsIntoIndex(index, "com.foo.app", descriptors)
        mergeStubsIntoIndex(index, "com.foo.app", descriptors)
        mergeStubsIntoIndex(index, "com.foo.app", descriptors)

        assertEquals(2, index.size())
        assertEquals(setOf("com.foo.app"), index.lookup("com.foo.IFoo"))
        assertEquals(setOf("com.foo.app"), index.lookup("com.foo.IBar"))
    }

    @Test
    fun `mergeStubsIntoIndex preserves entries from other packages`() {
        // 全局 scan 跑过之后留了一些其它包的索引;增量补一个新包不应擦掉它们。
        index.addEntry("android.app.IActivityManager", "android")
        index.addEntry("com.shared.IShared", "com.first.app")

        mergeStubsIntoIndex(index, "com.second.app", listOf("com.shared.IShared", "com.second.IOnly"))

        assertEquals(setOf("android"), index.lookup("android.app.IActivityManager"))
        // 同一接口名在两个包里 → 集合累积
        assertEquals(setOf("com.first.app", "com.second.app"), index.lookup("com.shared.IShared"))
        assertEquals(setOf("com.second.app"), index.lookup("com.second.IOnly"))
    }

    @Test
    fun `mergeStubsIntoIndex empty descriptors is a no-op`() {
        index.addEntry("com.foo.IFoo", "com.foo.app")
        mergeStubsIntoIndex(index, "com.empty.app", emptyList())
        assertEquals(1, index.size())
        assertEquals(setOf("com.foo.app"), index.lookup("com.foo.IFoo"))
        // 空包名也不应崩
        mergeStubsIntoIndex(index, "", emptyList())
        assertEquals(1, index.size())
    }

    @Test
    fun `mergeStubsIntoIndex skips blank descriptor strings`() {
        // DESCRIPTOR 字段拿到空串不该污染索引(InterfaceIndex.addEntry 已经守了一道,这里再 fail-safe)
        mergeStubsIntoIndex(index, "com.foo.app", listOf("com.foo.IFoo", "", "com.foo.IBar"))
        assertEquals(2, index.size())
        assertTrue(index.lookup("").isEmpty())
    }
}
