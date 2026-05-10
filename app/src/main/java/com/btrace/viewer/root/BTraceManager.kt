package com.btrace.viewer.root

import android.content.Context
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BTrace进程管理器
 * 负责btrace二进制的释放、启动、停止等管理
 */
@Singleton
class BTraceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootManager: RootManager
) {
    @Volatile
    private var lastStartedPid: Int = -1

    companion object {
        private const val TAG = "BTraceManager"
        const val DEFAULT_BTRACE_PATH = "/data/local/tmp/btrace"
        const val DEFAULT_LISTEN_HOST = "127.0.0.1"
        const val DEFAULT_LISTEN_PORT = 47291
        const val DEFAULT_LISTEN_ADDR = "127.0.0.1:47291"
        private const val PID_FILE = "/data/local/tmp/btrace.pid"

        /**
         * 严格 PID 解析:每行第一个 whitespace-token 必须是纯十进制数字才算。
         *
         * spec § 7:不再从 `1234 com.btrace.viewer` 这种整行里 regex 抓数字。
         * 提到 companion 里暴露为 [parsePidsStrict],方便单元测试而不需要 BTraceManager 实例。
         */
        fun parsePidsStrict(lines: List<String>): List<Int> {
            val pids = LinkedHashSet<Int>()
            lines.forEach { line ->
                val token = line.trim().substringBefore(' ').substringBefore('\t')
                if (token.isEmpty()) return@forEach
                if (token.all { it in '0'..'9' }) {
                    token.toIntOrNull()?.takeIf { it > 1 }?.let { pids.add(it) }
                }
            }
            return pids.toList()
        }
    }

    /**
     * 默认监听地址
     */
    fun getListenHost(): String = DEFAULT_LISTEN_HOST

    fun getListenPort(): Int = DEFAULT_LISTEN_PORT

    fun getListenAddr(): String = DEFAULT_LISTEN_ADDR

    private fun shellPathPrefix(): String {
        return "export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/product/bin:/vendor/bin:\$PATH; "
    }

    private suspend fun execWithPath(command: String) =
        rootManager.executeInGlobalMountNamespace(shellPathPrefix() + command)

    /**
     * 一次性 su 进程执行命令,与 libsu 的常驻 shell 完全隔离。
     *
     * 用途:启动 btrace daemon。在 libsu 的常驻 shell 里 `nohup ... &` 后台 daemon
     * 会污染 shell 的 stdout 通道(实测:`echo MARKER` 都拿不到回显,后续命令的 stdout
     * 全空 5+ 分钟,只有重建 shell 才恢复)。每次启 daemon 起一个独立 su 进程,
     * 进程退出 = stdout 通道关闭,libsu 的常驻 shell 完全不受影响。
     *
     * 返回 (exitCode, mergedOutput)。stderr/stdout 合并方便调用方一次拿。
     */
    private suspend fun execStandaloneRoot(command: String, timeoutMs: Long = 5000): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    CLogUtils.w(TAG, "execStandaloneRoot() 超时 ${timeoutMs}ms,强杀")
                    process.destroyForcibly()
                    return@withContext Pair(-1, output)
                }
                Pair(process.exitValue(), output)
            } catch (e: Exception) {
                CLogUtils.e(TAG, "execStandaloneRoot() 异常: ${e.message}", e)
                Pair(-1, "")
            }
        }

    private fun parsePids(lines: List<String>): List<Int> = parsePidsStrict(lines)

    private fun parseFirstPid(lines: List<String>): Int {
        return parsePids(lines).firstOrNull() ?: -1
    }

    private suspend fun isPidAlive(pid: Int): Boolean {
        if (pid <= 1) return false
        val result = execWithPath("kill -0 $pid")
        return result.isSuccess
    }

    private suspend fun getPidFromPidFile(): Int {
        val result = execWithPath("cat '$PID_FILE'")
        return parseFirstPid(result.out + result.err)
    }

    /**
     * spec § 7.4.4:PID 文件如果指向已死进程,主动清掉,避免下次误判 "daemon 还活着"。
     * 由 startBTrace / stopBTrace 在合适时机调用。
     */
    private suspend fun cleanupStalePidFile() {
        val filePid = getPidFromPidFile()
        if (filePid > 1 && !isPidAlive(filePid)) {
            CLogUtils.i(TAG, "cleanupStalePidFile() PID 文件指向已死进程 $filePid,清理")
            execWithPath("rm -f '$PID_FILE'")
        }
    }

    /**
     * 查询 btrace daemon PID。spec § 7.2/7.3 只允许三种精确来源:
     *
     *   1. `pidof btrace`            —— 内核任务名严格等于 "btrace"(15 字符以内)
     *   2. `pgrep -f /data/local/tmp/btrace` —— 命令行包含完整绝对路径
     *   3. `ps -A -o PID,NAME` + awk 第二列严格 == "btrace"
     *
     * 已删除的危险命令:
     *   - `pgrep -f '(^|/)btrace(\s|$)'`:正则匹配命令行末尾,可能误中 `/sbin/btraceWrapper`
     *     这种第三方进程
     *   - `ps -A | grep '[b]trace'`:子串匹配,且整行解析(虽 grep 巧用 `[b]` 防止 grep
     *     自己被命中,但仍会命中 `com.btrace.viewer` 自己 —— 系统某些 ROM 输出的进程行
     *     格式不固定,parsePids 收紧后也能挡,但根上不让流入更稳)
     */
    private suspend fun queryBTracePids(): List<Int> {
        val commands = listOf(
            "pidof btrace",
            "pgrep -f '$DEFAULT_BTRACE_PATH'",
            "ps -A -o PID,NAME | awk '\$2==\"btrace\"{print \$1}'"
        )
        for (command in commands) {
            val result = execWithPath(command)
            val pids = parsePids(result.out + result.err)
            CLogUtils.d(
                TAG,
                "queryBTracePids() cmd='$command', code=${result.code}, parsed=${pids.joinToString()}"
            )
            if (pids.isNotEmpty()) {
                return pids
            }
        }
        return emptyList()
    }
    
    /**
     * 检查btrace进程是否正在运行
     * @return true 如果btrace进程正在运行
     */
    suspend fun isBTraceRunning(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isPidAlive(lastStartedPid)) {
                CLogUtils.d(TAG, "isBTraceRunning() 通过内存PID判断为运行中: pid=$lastStartedPid")
                return@withContext true
            }

            val filePid = getPidFromPidFile()
            if (isPidAlive(filePid)) {
                lastStartedPid = filePid
                CLogUtils.d(TAG, "isBTraceRunning() 通过PID文件判断为运行中: pid=$filePid")
                return@withContext true
            }

            val pids = queryBTracePids()
            val isRunning = pids.isNotEmpty()
            CLogUtils.d(TAG, "isBTraceRunning() 检查结果: $isRunning, pids=${pids.joinToString()}")
            isRunning
        } catch (e: Exception) {
            CLogUtils.w(TAG, "isBTraceRunning() 检查异常: ${e.message}")
            false
        }
    }

    /**
     * 获取btrace进程的PID
     * @return btrace进程PID，如果未运行返回-1
     */
    suspend fun getBTracePid(): Int = withContext(Dispatchers.IO) {
        try {
            if (isPidAlive(lastStartedPid)) {
                return@withContext lastStartedPid
            }

            val filePid = getPidFromPidFile()
            if (isPidAlive(filePid)) {
                lastStartedPid = filePid
                return@withContext filePid
            }

            val pids = queryBTracePids()
            if (pids.isNotEmpty()) {
                val pid = pids.first()
                CLogUtils.d(TAG, "getBTracePid() pid=$pid")
                pid
            } else {
                CLogUtils.d(TAG, "getBTracePid() 进程未运行")
                -1
            }
        } catch (e: Exception) {
            CLogUtils.w(TAG, "getBTracePid() 异常: ${e.message}")
            -1
        }
    }

    /**
     * 从assets释放btrace二进制文件到指定路径
     * @param targetPath 目标路径
     * @return true 如果释放成功
     */
    suspend fun extractBTrace(targetPath: String): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.i(TAG, "extractBTrace() 开始释放btrace到: $targetPath")
        try {
            // 检查 assets 中是否有 btrace
            val assetsList = context.assets.list("")?.toList() ?: emptyList()
            CLogUtils.d(TAG, "extractBTrace() assets列表: ${assetsList.joinToString()}")
            
            if (!assetsList.contains("btrace")) {
                CLogUtils.e(TAG, "extractBTrace() assets中未找到btrace文件")
                return@withContext false
            }
            
            // 从assets读取btrace二进制
            val inputStream = context.assets.open("btrace")
            val tempFile = File(context.cacheDir, "btrace_temp")
            CLogUtils.d(TAG, "extractBTrace() 临时文件: ${tempFile.absolutePath}")

            // 先写入到应用缓存目录
            tempFile.outputStream().use { outputStream ->
                val bytesCopied = inputStream.copyTo(outputStream)
                CLogUtils.d(TAG, "extractBTrace() 复制到临时文件: $bytesCopied bytes")
            }
            CLogUtils.d(TAG, "extractBTrace() 临时文件状态: exists=${tempFile.exists()}, size=${tempFile.length()}")

            // 使用root权限复制到目标路径（在全局挂载命名空间中）
            // 先确保目标目录存在
            val targetDir = File(targetPath).parent
            if (targetDir != null) {
                execWithPath("mkdir -p '$targetDir'")
            }
            
            val copyCmd = "cp '${tempFile.absolutePath}' '$targetPath' && chmod 755 '$targetPath'"
            CLogUtils.d(TAG, "extractBTrace() 执行命令: $copyCmd")
            val copyResult = execWithPath(copyCmd)
            CLogUtils.d(TAG, "extractBTrace() 命令结果: isSuccess=${copyResult.isSuccess}, code=${copyResult.code}")
            if (!copyResult.isSuccess) {
                CLogUtils.e(TAG, "extractBTrace() 命令失败: stdout=${copyResult.out.joinToString()}, stderr=${copyResult.err.joinToString()}")
                
                // 尝试分步执行以获得更好的错误信息
                CLogUtils.d(TAG, "extractBTrace() 尝试分步复制...")
                val cpResult = execWithPath("cp '${tempFile.absolutePath}' '$targetPath'")
                CLogUtils.d(TAG, "extractBTrace() cp结果: isSuccess=${cpResult.isSuccess}, stderr=${cpResult.err.joinToString()}")
                
                if (cpResult.isSuccess) {
                    val chmodResult = execWithPath("chmod 755 '$targetPath'")
                    CLogUtils.d(TAG, "extractBTrace() chmod结果: isSuccess=${chmodResult.isSuccess}, stderr=${chmodResult.err.joinToString()}")
                }
            }

            // 清理临时文件
            tempFile.delete()

            copyResult.isSuccess
        } catch (e: Exception) {
            CLogUtils.e(TAG, "extractBTrace() 异常: ${e.message}", e)
            false
        }
    }

    /**
     * 检查btrace二进制是否存在于指定路径
     * @param btracePath btrace二进制路径
     * @return true 如果存在
     */
    suspend fun isBTraceInstalled(btracePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execWithPath("test -x '$btracePath'")
            val installed = result.isSuccess
            CLogUtils.d(
                TAG,
                "isBTraceInstalled() path=$btracePath, installed=$installed, code=${result.code}, out=${result.out.joinToString()}, err=${result.err.joinToString()}"
            )
            if (!installed) {
                val diag = execWithPath("ls -la '$btracePath'")
                CLogUtils.w(
                    TAG,
                    "isBTraceInstalled() 诊断: code=${diag.code}, out=${diag.out.joinToString()}, err=${diag.err.joinToString()}"
                )
            }
            installed
        } catch (e: Exception) {
            CLogUtils.w(TAG, "isBTraceInstalled() 检查异常: ${e.message}")
            false
        }
    }

    /**
     * 启动btrace守护进程
     * @param btracePath btrace二进制路径
     * @param listenAddr 监听地址 (host:port)
     * @param targetUid 目标UID，0表示不过滤
     * @return true 如果启动成功
     */
    suspend fun startBTrace(
        btracePath: String,
        listenAddr: String,
        targetUid: Int,
        sessionToken: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.i(TAG, "startBTrace() 开始启动, btracePath=$btracePath, listenAddr=$listenAddr, targetUid=$targetUid, hasToken=${sessionToken.isNotEmpty()}")
        if (targetUid <= 0) {
            // uid=0 在 BPF 端表示不过滤,会导致全系统 binder 事件涌入,通道很快爆量。
            // 必须先选择具体应用才能启动监控。
            CLogUtils.e(TAG, "startBTrace() 拒绝启动: targetUid 必须 > 0,实际=$targetUid。请先在应用列表选择目标。")
            return@withContext false
        }
        try {
            // 始终尝试覆盖释放，确保设备端二进制与APK assets保持一致，避免旧版本逻辑残留。
            val isInstalledBefore = isBTraceInstalled(btracePath)
            CLogUtils.d(TAG, "startBTrace() 启动前已安装: $isInstalledBefore")
            CLogUtils.i(TAG, "startBTrace() 开始覆盖释放最新btrace")
            val extracted = extractBTrace(btracePath)
            CLogUtils.d(TAG, "startBTrace() 覆盖释放结果: $extracted")
            if (!extracted) {
                CLogUtils.e(TAG, "startBTrace() btrace覆盖释放失败")
                if (isInstalledBefore) {
                    CLogUtils.w(TAG, "startBTrace() 使用已存在二进制继续尝试启动")
                } else {
                    CLogUtils.w(TAG, "startBTrace() 未检测到可用二进制，继续尝试启动以输出诊断日志")
                }
            }

            // 启动btrace守护进程(daemon 模式是唯一模式,无需 -d 标志)
            //
            // ⚠️ libsu 使用 FLAG_REDIRECT_STDERR 把 stderr 合并到 stdout 作为自己的解析流。
            // 直接 `nohup X &` 会触发 shell 的 job-control 消息(例如 `[1] 22052`),
            // 这些消息经 stderr 流入 libsu 的输出缓冲,导致后续所有命令的 stdout 无法被正确
            // 切分(表现为 pidof/pgrep/cat 返回空)。解决办法:
            //   1) 用 `{ ... } 2>/dev/null` 把整组的 stderr(含 job-control 噪音)扔掉
            //   2) 显式 `</dev/null` 关 stdin,避免 daemon 继承 shell 的 stdin 产生反压
            val launchLog = "/data/local/tmp/btrace.daemon.log"
            // spec § 8.2:PID 文件归属切回 daemon 自己写。但 libsu 的 shell 是异步行驱动:
            // 后台启动 daemon 后命令立即返回,App 端 sleep 估算时长不可靠
            // (多次实测 250ms 后 cat 仍读不到,根本原因可能是 page-cache 写一致性 +
            //  常驻 root shell 状态 — 太脆弱,不值得在这条路径上较劲)。
            //
            // 解决方案:在同一条 shell 命令里 poll 等 PID 文件出现,并把内容 cat 出来。
            // 这把同步点从 App 端 sleep 移到 shell 内部 —— libsu 自然等到 cat 完成才返回,
            // 且 cat 输出会被它当成一行 stdout 收集供后续 parsePids 解析。
            //
            // spec § 8.4:把会话令牌通过 -t 透给 daemon,daemon 在 SetTarget 的 ACK 里回传校验。
            val tokenArg = if (sessionToken.isNotEmpty()) " -t '$sessionToken'" else ""
            // 用一次性 su 进程启动 daemon —— 不能复用 libsu 常驻 shell。
            // 实测 libsu shell 在跑 `nohup ... &` 后,stdout 通道被破坏,后续所有命令
            // (包括完全无关的 cat / pidof / echo MARKER)输出全空,持续直到重建 shell。
            // 推测:libsu 通过 stdout 上的 marker 行来判断命令边界;daemon 即便 redirect
            // 了 fd,fork 期间短暂继承的 fd 仍可能让某个内部 marker 提前出现,libsu
            // 把后续的真实命令输出都"消费给上一个等待中的 marker"了。
            val launchCmd = "${shellPathPrefix()} { nohup $btracePath -l '$listenAddr' -u $targetUid$tokenArg " +
                    "</dev/null >'$launchLog' 2>&1 & } 2>/dev/null"
            CLogUtils.i(TAG, "startBTrace() 执行启动命令(独立 su): $launchCmd")
            val (launchCode, launchOut) = execStandaloneRoot(launchCmd, timeoutMs = 3000)
            CLogUtils.d(TAG, "startBTrace() 启动命令结果: code=$launchCode, out='$launchOut'")
            if (launchCode != 0) {
                CLogUtils.e(TAG, "startBTrace() 启动命令失败: code=$launchCode, out='$launchOut'")
                return@withContext false
            }

            // 给 daemon acquireSingleton 完成时间(实测 ~150ms,留 600ms 缓冲)。
            // PID 文件读取也用独立 su 进程,与 daemon 启动隔离 + 与后续 libsu 调用解耦。
            Thread.sleep(600)
            val (catCode, catOut) = execStandaloneRoot("cat '$PID_FILE' 2>/dev/null", timeoutMs = 1500)
            val launchedPid = catOut.trim().lineSequence()
                .firstOrNull()
                ?.takeIf { it.all { c -> c in '0'..'9' } }
                ?.toIntOrNull()
                ?: -1
            if (launchedPid > 1 && isPidAlive(launchedPid)) {
                lastStartedPid = launchedPid
                CLogUtils.i(TAG, "startBTrace() daemon 已启动: pid=$launchedPid")
            } else {
                val (logCode, logOut) = execStandaloneRoot("tail -n 40 '$launchLog' 2>/dev/null", timeoutMs = 1500)
                CLogUtils.w(
                    TAG,
                    "startBTrace() PID 未拿到 (catCode=$catCode, out='$catOut')。启动日志(code=$logCode):\n$logOut"
                )
                if (!isBTraceRunning()) {
                    CLogUtils.e(TAG, "startBTrace() 进程也查不到,启动失败")
                    return@withContext false
                }
            }
            CLogUtils.i(TAG, "startBTrace() 启动完成")
            true
        } catch (e: Exception) {
            CLogUtils.e(TAG, "startBTrace() 异常: ${e.message}", e)
            false
        }
    }

    /**
     * 停止btrace进程
     * @return true 如果停止成功
     */
    suspend fun stopBTrace(): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.i(TAG, "stopBTrace() 开始停止btrace进程")
        try {
            // spec § 7.4 清场顺序:
            //   1) 优先按内存 / PID 文件里的精确 PID 杀
            //   2) 否则用精确路径 + 进程名兜底(`-f` 全路径 + `-x btrace` 名字精确)
            //      绝不用裸 `pkill -f btrace` —— App 自己进程的命令行就含 "btrace"。
            val pid = getBTracePid()
            if (pid > 1) {
                val killResult = execWithPath("kill $pid")
                CLogUtils.d(TAG, "stopBTrace() kill结果: isSuccess=${killResult.isSuccess}, code=${killResult.code}")
            } else {
                val result = execWithPath("pkill -f '$DEFAULT_BTRACE_PATH' ; pkill -x btrace")
                CLogUtils.d(TAG, "stopBTrace() pkill结果: isSuccess=${result.isSuccess}, code=${result.code}")
            }
            // 等待进程完全退出
            Thread.sleep(200)
            val stopped = !isBTraceRunning()
            lastStartedPid = -1
            // 无论停止是否完全成功,都尝试清理失效 PID 文件 —— 避免下次启动误判
            // "旧实例还活着"(spec § 7.4.4 要求即便兜底失败也要清残留)。
            cleanupStalePidFile()
            // 进程确实没了的话,把 PID 文件也直接干掉
            if (stopped) {
                execWithPath("rm -f '$PID_FILE'")
            }
            CLogUtils.i(TAG, "stopBTrace() 停止结果: $stopped")
            stopped
        } catch (e: Exception) {
            CLogUtils.e(TAG, "stopBTrace() 异常: ${e.message}")
            false
        }
    }
}
