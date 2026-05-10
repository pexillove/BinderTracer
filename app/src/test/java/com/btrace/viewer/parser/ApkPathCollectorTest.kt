package com.btrace.viewer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec § 6.4 / review 2026-05-02 [P1] —— split APK 双路径加载的"路径收集 + 拼接"纯逻辑单测。
 *
 * 把 ApplicationInfo.sourceDir + splitSourceDirs 抽到 [ApkPathCollector] 之后,
 * 这里覆盖所有边界,避免依赖 PackageManager / Robolectric。
 */
class ApkPathCollectorTest {

    // ---------- collectApkPaths ----------

    @Test
    fun `collectApkPaths only base when splits null`() {
        val paths = ApkPathCollector.collectApkPaths("/data/app/foo/base.apk", null)
        assertEquals(listOf("/data/app/foo/base.apk"), paths)
    }

    @Test
    fun `collectApkPaths only base when splits empty`() {
        val paths = ApkPathCollector.collectApkPaths("/data/app/foo/base.apk", emptyArray())
        assertEquals(listOf("/data/app/foo/base.apk"), paths)
    }

    @Test
    fun `collectApkPaths base plus single split`() {
        val paths = ApkPathCollector.collectApkPaths(
            "/data/app/foo/base.apk",
            arrayOf("/data/app/foo/split_a.apk")
        )
        assertEquals(
            listOf("/data/app/foo/base.apk", "/data/app/foo/split_a.apk"),
            paths
        )
    }

    @Test
    fun `collectApkPaths base plus multiple splits preserves order`() {
        val paths = ApkPathCollector.collectApkPaths(
            "/data/app/foo/base.apk",
            arrayOf(
                "/data/app/foo/split_a.apk",
                "/data/app/foo/split_b.apk",
                "/data/app/foo/split_c.apk"
            )
        )
        assertEquals(
            listOf(
                "/data/app/foo/base.apk",
                "/data/app/foo/split_a.apk",
                "/data/app/foo/split_b.apk",
                "/data/app/foo/split_c.apk"
            ),
            paths
        )
    }

    @Test
    fun `collectApkPaths drops null and blank entries inside splits`() {
        val paths = ApkPathCollector.collectApkPaths(
            "/data/app/foo/base.apk",
            arrayOf("/data/app/foo/split_a.apk", null, "", "  ", "/data/app/foo/split_b.apk")
        )
        assertEquals(
            listOf(
                "/data/app/foo/base.apk",
                "/data/app/foo/split_a.apk",
                "/data/app/foo/split_b.apk"
            ),
            paths
        )
    }

    @Test
    fun `collectApkPaths returns empty when sourceDir null and splits null`() {
        assertTrue(ApkPathCollector.collectApkPaths(null, null).isEmpty())
    }

    @Test
    fun `collectApkPaths returns splits only when sourceDir blank`() {
        val paths = ApkPathCollector.collectApkPaths(
            null,
            arrayOf("/data/app/foo/split_a.apk")
        )
        assertEquals(listOf("/data/app/foo/split_a.apk"), paths)
    }

    @Test
    fun `collectApkPaths dedupes when sourceDir also appears in splits`() {
        val paths = ApkPathCollector.collectApkPaths(
            "/data/app/foo/base.apk",
            arrayOf("/data/app/foo/base.apk", "/data/app/foo/split_a.apk")
        )
        assertEquals(
            listOf("/data/app/foo/base.apk", "/data/app/foo/split_a.apk"),
            paths
        )
    }

    // ---------- joinDexPath ----------

    @Test
    fun `joinDexPath single returns string with no separator`() {
        assertEquals(
            "/data/app/foo/base.apk",
            ApkPathCollector.joinDexPath(listOf("/data/app/foo/base.apk"))
        )
    }

    @Test
    fun `joinDexPath multi joins with colon`() {
        assertEquals(
            "/data/app/foo/base.apk:/data/app/foo/split_a.apk:/data/app/foo/split_b.apk",
            ApkPathCollector.joinDexPath(
                listOf(
                    "/data/app/foo/base.apk",
                    "/data/app/foo/split_a.apk",
                    "/data/app/foo/split_b.apk"
                )
            )
        )
    }

    @Test
    fun `joinDexPath empty returns empty string`() {
        assertEquals("", ApkPathCollector.joinDexPath(emptyList()))
    }

    // ---------- composeLoaderDexPath ----------

    @Test
    fun `composeLoaderDexPath copied base only`() {
        val composed = ApkPathCollector.composeLoaderDexPath(
            copiedBase = "/data/data/btrace/files/dex_cache/com.foo.apk",
            originalSplits = null
        )
        assertEquals(
            "/data/data/btrace/files/dex_cache/com.foo.apk",
            composed
        )
    }

    @Test
    fun `composeLoaderDexPath copied base plus original splits`() {
        val composed = ApkPathCollector.composeLoaderDexPath(
            copiedBase = "/data/data/btrace/files/dex_cache/com.foo.apk",
            originalSplits = arrayOf(
                "/data/app/foo/split_a.apk",
                "/data/app/foo/split_b.apk"
            )
        )
        assertEquals(
            "/data/data/btrace/files/dex_cache/com.foo.apk:" +
                "/data/app/foo/split_a.apk:" +
                "/data/app/foo/split_b.apk",
            composed
        )
    }

    @Test
    fun `composeLoaderDexPath drops null and blank splits`() {
        val composed = ApkPathCollector.composeLoaderDexPath(
            copiedBase = "/data/data/btrace/files/dex_cache/com.foo.apk",
            originalSplits = arrayOf("", "  ", null, "/data/app/foo/split_a.apk")
        )
        assertEquals(
            "/data/data/btrace/files/dex_cache/com.foo.apk:/data/app/foo/split_a.apk",
            composed
        )
    }

    @Test
    fun `composeLoaderDexPath empty splits array yields just base`() {
        val composed = ApkPathCollector.composeLoaderDexPath(
            copiedBase = "/data/data/btrace/files/dex_cache/com.foo.apk",
            originalSplits = emptyArray()
        )
        assertEquals(
            "/data/data/btrace/files/dex_cache/com.foo.apk",
            composed
        )
    }
}
