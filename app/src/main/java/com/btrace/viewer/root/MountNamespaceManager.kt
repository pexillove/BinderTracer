package com.btrace.viewer.root

import com.btrace.viewer.utils.CLogUtils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 挂载命名空间管理器
 * 提供全局挂载命名空间和相关操作的高级封装
 */
@Singleton
class MountNamespaceManager @Inject constructor(
    private val rootManager: RootManager
) {
    
    companion object {
        private const val TAG = "MountNamespaceManager"
        
        // 挂载命名空间相关路径
        private const val PROC_MOUNTS = "/proc/mounts"
        private const val PROC_SELF_MOUNTINFO = "/proc/self/mountinfo"
        private const val GLOBAL_MOUNT_NS = "/proc/1/ns/mnt"
    }
    
    /**
     * 检查是否在全局挂载命名空间中运行
     * @return true 如果在全局挂载命名空间中
     */
    suspend fun isInGlobalMountNamespace(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 通过比较当前进程和init进程的挂载命名空间ID来判断
            val currentNsResult = rootManager.executeInGlobalMountNamespace("readlink /proc/self/ns/mnt")
            val initNsResult = rootManager.executeInGlobalMountNamespace("readlink /proc/1/ns/mnt")
            
            if (currentNsResult.isSuccess && initNsResult.isSuccess) {
                val currentNs = currentNsResult.out.firstOrNull()?.trim()
                val initNs = initNsResult.out.firstOrNull()?.trim()
                val isGlobal = currentNs == initNs
                CLogUtils.d(TAG, "isInGlobalMountNamespace() current=$currentNs, init=$initNs, isGlobal=$isGlobal")
                isGlobal
            } else {
                CLogUtils.w(TAG, "isInGlobalMountNamespace() 无法读取挂载命名空间信息")
                false
            }
        } catch (e: Exception) {
            CLogUtils.e(TAG, "isInGlobalMountNamespace() 异常: ${e.message}")
            false
        }
    }
    
    /**
     * 获取当前挂载点信息
     * @return 挂载点信息列表
     */
    suspend fun getMountPoints(): List<String> = withContext(Dispatchers.IO) {
        try {
            val result = rootManager.executeInGlobalMountNamespace("cat $PROC_MOUNTS")
            if (result.isSuccess) {
                val mountPoints = result.out.map { line ->
                    line.split(" ").getOrNull(1) ?: ""
                }.filter { it.isNotEmpty() }
                CLogUtils.d(TAG, "getMountPoints() 找到 ${mountPoints.size} 个挂载点")
                mountPoints
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            CLogUtils.e(TAG, "getMountPoints() 异常: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * 检查指定路径是否可访问
     * @param path 要检查的路径
     * @return true 如果路径可访问
     */
    suspend fun isPathAccessible(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = rootManager.executeInGlobalMountNamespace("test -r '$path' && echo 'readable'")
            val accessible = result.isSuccess && result.out.any { it.contains("readable") }
            CLogUtils.d(TAG, "isPathAccessible() path=$path, accessible=$accessible")
            accessible
        } catch (e: Exception) {
            CLogUtils.e(TAG, "isPathAccessible() 异常: ${e.message}")
            false
        }
    }
    
    /**
     * 在全局挂载命名空间中创建符号链接
     * @param target 目标路径
     * @param link 链接路径
     * @return true 如果创建成功
     */
    suspend fun createSymlinkInGlobalNamespace(target: String, link: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = rootManager.executeInGlobalMountNamespace("ln -sf '$target' '$link'")
            val success = result.isSuccess
            CLogUtils.d(TAG, "createSymlinkInGlobalNamespace() target=$target, link=$link, success=$success")
            if (!success) {
                CLogUtils.e(TAG, "createSymlinkInGlobalNamespace() 失败: ${result.err.joinToString()}")
            }
            success
        } catch (e: Exception) {
            CLogUtils.e(TAG, "createSymlinkInGlobalNamespace() 异常: ${e.message}")
            false
        }
    }
    
    /**
     * 获取全局挂载命名空间中的文件状态
     * @param path 文件路径
     * @return 文件状态信息
     */
    suspend fun getFileStatusInGlobalNamespace(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = rootManager.executeInGlobalMountNamespace("stat '$path'")
            if (result.isSuccess) {
                val status = result.out.joinToString("\n")
                CLogUtils.d(TAG, "getFileStatusInGlobalNamespace() path=$path, status=$status")
                status
            } else {
                CLogUtils.w(TAG, "getFileStatusInGlobalNamespace() 无法获取文件状态: ${result.err.joinToString()}")
                null
            }
        } catch (e: Exception) {
            CLogUtils.e(TAG, "getFileStatusInGlobalNamespace() 异常: ${e.message}")
            null
        }
    }
}
