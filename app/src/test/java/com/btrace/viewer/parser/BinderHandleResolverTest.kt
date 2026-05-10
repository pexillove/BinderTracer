package com.btrace.viewer.parser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolver 解析两路 binderfs 文本 + cmdline 反查的单元测试。
 *
 * 不触真 root shell —— 通过 internal var execRoot 注入 fake。
 */
class BinderHandleResolverTest {

    @Test
    fun parseNodeOwnerMap_extractsNodeIdAndOwnerPid() {
        val text = """
            binder state:
            dead nodes:
              node 1152967: ub.. cb.. pri 0:139 hs 1 hw 1 ls 0 lw 0 is 1 iw 1 tr 1 proc 28317
              node 1151212: ub.. cb.. pri 0:139 hs 1 hw 1 ls 0 lw 0 is 1 iw 1 tr 1 proc 1298
            live nodes:
              node 13547: ub.. cb.. pri 0:139 hs 1 hw 1 ls 0 lw 0 is 1 iw 1 tr 1 proc 1298
              node 9991: ub.. cb.. pri 0:139 hs 1 hw 1 ls 0 lw 0 is 2 iw 2 tr 1 proc 1086 2223
        """.trimIndent()

        val resolver = BinderHandleResolver()
        val map = resolver.parseNodeOwnerMap(text)
        assertEquals(28317, map[1152967])
        assertEquals(1298, map[1151212])
        assertEquals(1298, map[13547])
        // 多 owner 时取第一个
        assertEquals(1086, map[9991])
    }

    @Test
    fun parseHandleToNode_onlyBinderContext() {
        val text = """
            binder proc state:
            proc 4578
            context hwbinder
              ref 1260835: desc 0 node 3 s 1 w 1 d 0
              ref 1260911: desc 1 node 1277 s 0 w 1 d 0
            binder proc state:
            proc 4578
            context vndbinder
              ref 1260537: desc 0 node 3 s 1 w 1 d 0
            binder proc state:
            proc 4578
            context binder
              ref 1258509: desc 0 node 1 s 1 w 1 d 0
              ref 1258525: desc 1 node 13547 s 1 w 1 d 0
              ref 1258597: desc 2 node 9991 s 1 w 1 d 0
        """.trimIndent()

        val resolver = BinderHandleResolver()
        val map = resolver.parseHandleToNode(text)
        // 只采 binder context 段 —— hwbinder / vndbinder 的 ref 不能混进来
        assertEquals(3, map.size)
        assertEquals(1, map[0])
        assertEquals(13547, map[1])
        assertEquals(9991, map[2])
    }

    @Test
    fun lookup_missReturnsNull_andTriggersRefresh() = runTest {
        val resolver = BinderHandleResolver()
        // 缓存空,任何 lookup 都 miss
        assertNull(resolver.lookup(senderPid = 1234, handle = 1L))
    }

    @Test
    fun refresh_thenLookup_returnsResolvedTarget() = runTest {
        val resolver = BinderHandleResolver()
        // 注入 fake shell:state / proc / cmdline 三段
        resolver.execRoot = { command ->
            when {
                command.contains("/dev/binderfs/binder_logs/state") -> 0 to """
                    binder state:
                    live nodes:
                      node 13547: ub.. cb.. proc 1086
                      node 9991: ub.. cb.. proc 2223
                """.trimIndent()
                command.contains("/dev/binderfs/binder_logs/proc/4578") -> 0 to """
                    binder proc state:
                    proc 4578
                    context binder
                      ref 1: desc 1 node 13547 s 1 w 1 d 0
                      ref 2: desc 2 node 9991 s 1 w 1 d 0
                """.trimIndent()
                command.contains("/proc/1086/cmdline") -> 0 to "system_server"
                command.contains("/proc/2223/cmdline") -> 0 to "com.android.systemui"
                else -> -1 to ""
            }
        }

        resolver.refresh(senderPid = 4578)

        val r1 = resolver.lookup(senderPid = 4578, handle = 1L)
        assertEquals(1086, r1?.ownerPid)
        assertEquals("system_server", r1?.processName)

        val r2 = resolver.lookup(senderPid = 4578, handle = 2L)
        assertEquals(2223, r2?.ownerPid)
        assertEquals("com.android.systemui", r2?.processName)

        // 不属于该 sender 的 pid → 不应命中
        assertNull(resolver.lookup(senderPid = 9999, handle = 1L))
    }

    @Test
    fun refresh_emptyState_keepsCacheUntouched() = runTest {
        val resolver = BinderHandleResolver()
        resolver.execRoot = { _ -> 0 to "" }
        resolver.refresh(senderPid = 4578)
        // state 解析为空 → 不写 cache,lookup 仍 miss
        assertNull(resolver.lookup(senderPid = 4578, handle = 1L))
    }

    @Test
    fun invalidate_clearsCache() = runTest {
        val resolver = BinderHandleResolver()
        resolver.execRoot = { command ->
            when {
                command.contains("state") -> 0 to "  node 100: ub.. cb.. proc 500"
                command.contains("proc/4578") -> 0 to """
                    proc 4578
                    context binder
                      ref 1: desc 1 node 100 s 1 w 1 d 0
                """.trimIndent()
                command.contains("/proc/500/cmdline") -> 0 to "init"
                else -> -1 to ""
            }
        }
        resolver.refresh(4578)
        assertEquals("init", resolver.lookup(4578, 1L)?.processName)

        resolver.invalidate()
        assertNull(resolver.lookup(4578, 1L))
    }
}
