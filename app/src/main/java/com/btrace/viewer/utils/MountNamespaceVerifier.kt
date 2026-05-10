package com.btrace.viewer.utils

import com.btrace.viewer.root.MountNamespaceManager
import com.btrace.viewer.root.RootManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局挂载命名空间验证工具
 * 用于验证libsu和全局挂载命名空间是否正常工作
 *
 * 调用入口收敛在设置页"运行环境诊断"按钮(review #4):冷启动不再自动跑十几条 root
 * 命令,所有结果都建模为 [EnvironmentCheckResult] 由 UI 渲染。
 */
@Singleton
class MountNamespaceVerifier @Inject constructor(
    private val rootManager: RootManager,
    private val mountNamespaceManager: MountNamespaceManager
) {

    companion object {
        private const val TAG = "MountNamespaceVerifier"
    }

    /**
     * 验证全局挂载命名空间功能
     * @return 验证结果
     */
    suspend fun verifyGlobalMountNamespace(): EnvironmentCheckResult {
        val results = mutableListOf<EnvironmentCheckItem>()

        // 1. 验证Root权限
        results.add(
            EnvironmentCheckItem(
                name = "Root权限检查",
                description = "检查是否获取到Root权限",
                success = rootManager.checkRoot(),
                details = if (rootManager.checkRoot()) "Root权限已获取" else "未获取到Root权限"
            )
        )

        // 2. 验证Shell状态
        val shellStatus = rootManager.getShellStatus()
        results.add(
            EnvironmentCheckItem(
                name = "Shell状态",
                description = "检查libsu Shell实例状态",
                success = shellStatus.contains("isRoot=true"),
                details = shellStatus
            )
        )

        // 3. 验证全局挂载命名空间
        val isGlobalNs = mountNamespaceManager.isInGlobalMountNamespace()
        results.add(
            EnvironmentCheckItem(
                name = "全局挂载命名空间",
                description = "检查是否在全局挂载命名空间中运行",
                success = isGlobalNs,
                details = if (isGlobalNs) "已在全局挂载命名空间中" else "未在全局挂载命名空间中"
            )
        )

        // 4. 验证关键路径访问
        val testPaths = listOf("/data/local/tmp", "/system/bin", "/proc")
        testPaths.forEach { path ->
            val accessible = mountNamespaceManager.isPathAccessible(path)
            results.add(
                EnvironmentCheckItem(
                    name = "路径访问: $path",
                    description = "检查路径 $path 是否可访问",
                    success = accessible,
                    details = if (accessible) "路径可访问" else "路径不可访问"
                )
            )
        }

        // 5. 验证挂载点获取
        val mountPoints = mountNamespaceManager.getMountPoints()
        results.add(
            EnvironmentCheckItem(
                name = "挂载点信息",
                description = "获取系统挂载点信息",
                success = mountPoints.isNotEmpty(),
                details = "找到 ${mountPoints.size} 个挂载点，包括: ${mountPoints.take(3).joinToString(", ")}"
            )
        )

        // 6. 验证命令执行
        try {
            val testResult = rootManager.executeInGlobalMountNamespace("echo 'global_namespace_test'")
            results.add(
                EnvironmentCheckItem(
                    name = "命令执行测试",
                    description = "在全局挂载命名空间中执行测试命令",
                    success = testResult.isSuccess && testResult.out.any { it.contains("global_namespace_test") },
                    details = if (testResult.isSuccess) {
                        "命令执行成功，输出: ${testResult.out.joinToString()}"
                    } else {
                        "命令执行失败: ${testResult.err.joinToString()}"
                    }
                )
            )
        } catch (e: Exception) {
            results.add(
                EnvironmentCheckItem(
                    name = "命令执行测试",
                    description = "在全局挂载命名空间中执行测试命令",
                    success = false,
                    details = "命令执行异常: ${e.message}"
                )
            )
        }

        val overallSuccess = results.all { it.success }

        return EnvironmentCheckResult(
            overallSuccess = overallSuccess,
            items = results,
            summary = if (overallSuccess) {
                "所有验证项目通过，全局挂载命名空间功能正常"
            } else {
                "部分验证项目失败，请检查配置和权限"
            }
        )
    }
}

/**
 * 环境检查结果
 */
data class EnvironmentCheckResult(
    val overallSuccess: Boolean,
    val items: List<EnvironmentCheckItem>,
    val summary: String
)

/**
 * 单项检查结果
 */
data class EnvironmentCheckItem(
    val name: String,
    val description: String,
    val success: Boolean,
    val details: String
)
