package com.btrace.viewer.data

import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCP 通信管理
 * App 作为 Server，btrace 作为 Client
 */
    @Singleton
class SocketClient @Inject constructor() {

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    // 串行化 writeFrame,防止多协程并发写 conn 导致 magic/type/length/payload 四段被穿插
    private val writeLock = Any()

    @Volatile
    private var isConnected = false
    @Volatile
    private var serverRunning = false

    companion object {
        private const val TAG = "SocketClient"

        const val FRAME_MAGIC: Byte = 0xBD.toByte()
        const val HEADER_LEN = 6
        // 协议 v2:事件 payload 头长 60B(spec § 6.2)。仅文档性质,实际解析见
        // BinderEvent.fromPayload —— App 端不再支持 v1。
        const val EVENT_HEADER_LEN = 60
        // 与 daemon 的 protocol.go MaxPayloadLen 对齐(1MB);超此值视为协议异常,
        // 避免对端发畸形 length 把 App 拖进 OOM
        const val MAX_PAYLOAD_LEN = 1 shl 20

        // 命令类型 (App → btrace)
        const val MSG_SET_TARGET: Byte = 0x01
        const val MSG_PAUSE: Byte = 0x02
        const val MSG_RESUME: Byte = 0x03
        const val MSG_SHUTDOWN: Byte = 0x04
        const val MSG_PING: Byte = 0x05

        // 事件类型 (btrace → App)
        const val MSG_BINDER_EVENT: Byte = 0x10
        const val MSG_ACK: Byte = 0x11
        const val MSG_ERROR: Byte = 0x12
        const val MSG_PONG: Byte = 0x13

        private const val ACCEPT_TIMEOUT_MS = 5000
        private const val RECONNECT_ACCEPT_TIMEOUT_MS = 6000
    }

    /**
     * 启动 TCP Server
     */
    suspend fun startServer(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "startServer() host=$host, port=$port")
        disconnect()

        return@withContext try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(host, port))
            serverSocket = server
            serverRunning = true
            CLogUtils.i(TAG, "startServer() 监听成功")
            true
        } catch (e: Exception) {
            CLogUtils.e(TAG, "startServer() 监听失败: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    /**
     * 等待 btrace 连接
     */
    suspend fun awaitClient(timeoutMs: Int = ACCEPT_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        awaitClientInternal(timeoutMs, "awaitClient()")
    }

    /**
     * 断开连接
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "disconnect() 断开连接")
        try {
            serverRunning = false
            closeClientLocked()
            serverSocket?.close()
            CLogUtils.d(TAG, "disconnect() 连接已断开")
        } catch (e: Exception) {
            CLogUtils.w(TAG, "disconnect() 关闭时异常: ${e.message}")
        } finally {
            serverSocket = null
        }
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = isConnected

    /**
     * 发送设置目标UID命令
     */
    suspend fun sendSetTarget(uid: Int): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "sendSetTarget() uid=$uid")
        val payload = ByteBuffer.allocate(4).putInt(uid).array()
        val result = writeFrame(MSG_SET_TARGET, payload)
        CLogUtils.d(TAG, "sendSetTarget() 发送结果: $result, payload=${CLogUtils.formatBytes(payload)}")
        result
    }

    /**
     * 发送 SetTarget 并等 daemon 回 Ack 帧。期间收到的 BinderEvent 帧直接丢弃
     * (此时 eventFlow 还没启动消费,事件只是临时回流)。
     *
     * @return true 仅当收到 MSG_ACK 且 Success=true;false 表示写失败、超时、或 daemon 回 Error。
     */
    suspend fun sendSetTargetAndAwaitAck(
        uid: Int,
        expectedSessionToken: String = "",
        timeoutMs: Int = 2000
    ): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "sendSetTargetAndAwaitAck() uid=$uid, timeoutMs=$timeoutMs, hasToken=${expectedSessionToken.isNotEmpty()}")
        val payload = ByteBuffer.allocate(4).putInt(uid).array()
        if (!writeFrame(MSG_SET_TARGET, payload)) {
            CLogUtils.e(TAG, "sendSetTargetAndAwaitAck() 写帧失败")
            return@withContext false
        }

        val originalTimeout = try {
            socket?.soTimeout ?: 0
        } catch (e: Exception) {
            0
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    CLogUtils.e(TAG, "sendSetTargetAndAwaitAck() 等待 Ack 超时")
                    return@withContext false
                }
                socket?.soTimeout = remaining.toInt().coerceAtLeast(50)
                val frame = readFrame()
                if (frame == null) {
                    CLogUtils.e(TAG, "sendSetTargetAndAwaitAck() readFrame 返回 null,连接已断")
                    return@withContext false
                }
                when (frame.type) {
                    MSG_ACK -> {
                        // payload: [success(1) | msgLen(4 BE) | msg]
                        val success = frame.payload.isNotEmpty() && frame.payload[0] == 1.toByte()
                        val ackMessage = parseAckMessage(frame.payload)
                        CLogUtils.i(TAG, "sendSetTargetAndAwaitAck() 收到 Ack, success=$success, msg='$ackMessage'")

                        if (!success) return@withContext false

                        // spec § 8.4 会话令牌校验:daemon 在 message 前缀里回传 `session=<token>;`。
                        // 期望令牌非空 → 必须严格匹配,否则视为旧实例残留抢会话,拒绝继续。
                        if (expectedSessionToken.isNotEmpty()) {
                            val expectedPrefix = "session=$expectedSessionToken;"
                            if (!ackMessage.startsWith(expectedPrefix)) {
                                CLogUtils.e(TAG, "sendSetTargetAndAwaitAck() 会话令牌不匹配,可能是旧实例抢连。expected='$expectedSessionToken', actual msg='$ackMessage'")
                                return@withContext false
                            }
                            CLogUtils.d(TAG, "sendSetTargetAndAwaitAck() 会话令牌校验通过")
                        }
                        return@withContext true
                    }
                    MSG_ERROR -> {
                        CLogUtils.e(TAG, "sendSetTargetAndAwaitAck() 收到 Error: ${CLogUtils.formatBytes(frame.payload)}")
                        return@withContext false
                    }
                    MSG_BINDER_EVENT -> {
                        // 握手期间到达的事件丢弃,event collector 还没起来
                        CLogUtils.v(TAG, "sendSetTargetAndAwaitAck() 丢弃握手期间的 BinderEvent")
                    }
                    else -> {
                        CLogUtils.v(TAG, "sendSetTargetAndAwaitAck() 忽略未知帧 type=0x${String.format("%02X", frame.type)}")
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        } finally {
            try {
                socket?.soTimeout = originalTimeout
            } catch (e: Exception) {
                CLogUtils.w(TAG, "sendSetTargetAndAwaitAck() 恢复 soTimeout 失败: ${e.message}")
            }
        }
    }

    /**
     * 发送暂停命令
     */
    suspend fun sendPause(): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "sendPause() 发送暂停命令")
        val result = writeFrame(MSG_PAUSE, ByteArray(0))
        CLogUtils.d(TAG, "sendPause() 发送结果: $result")
        result
    }

    /**
     * 发送恢复命令
     */
    suspend fun sendResume(): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "sendResume() 发送恢复命令")
        val result = writeFrame(MSG_RESUME, ByteArray(0))
        CLogUtils.d(TAG, "sendResume() 发送结果: $result")
        result
    }

    /**
     * 发送关闭命令
     */
    suspend fun sendShutdown(): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.d(TAG, "sendShutdown() 发送关闭命令")
        val result = writeFrame(MSG_SHUTDOWN, ByteArray(0))
        CLogUtils.d(TAG, "sendShutdown() 发送结果: $result")
        result
    }

    /**
     * 发送心跳命令
     */
    suspend fun sendPing(): Boolean = withContext(Dispatchers.IO) {
        CLogUtils.v(TAG, "sendPing() 发送心跳")
        val result = writeFrame(MSG_PING, ByteArray(0))
        CLogUtils.v(TAG, "sendPing() 结果: $result")
        result
    }

    /**
     * 从 ACK payload 抽出 message 字段。
     *
     * Payload layout (与 daemon AckPayload.Encode 对齐):
     *   [success: 1 byte][msgLen: 4 bytes BE][msg: msgLen bytes UTF-8]
     *
     * 任何越界 / 长度不合法 → 返回空串(由调用方决定怎么处理)。
     */
    private fun parseAckMessage(payload: ByteArray): String {
        if (payload.size < 5) return ""
        val msgLen = ByteBuffer.wrap(payload, 1, 4).int
        if (msgLen <= 0 || 5 + msgLen > payload.size) return ""
        return String(payload, 5, msgLen, Charsets.UTF_8)
    }

    /**
     * 写入帧
     */
    private fun writeFrame(type: Byte, payload: ByteArray): Boolean {
        return synchronized(writeLock) {
            try {
                CLogUtils.v(TAG, "writeFrame() type=0x${String.format("%02X", type)}, payloadSize=${payload.size}")
                val outputStream = output
                if (outputStream == null) {
                    CLogUtils.w(TAG, "writeFrame() 输出流为空，当前未连接")
                    return@synchronized false
                }
                outputStream.writeByte(FRAME_MAGIC.toInt())
                outputStream.writeByte(type.toInt())
                outputStream.writeInt(payload.size)
                outputStream.write(payload)
                outputStream.flush()
                CLogUtils.v(TAG, "writeFrame() 帧写入成功")
                true
            } catch (e: Exception) {
                CLogUtils.e(TAG, "writeFrame() 写入失败: ${e.message}", e)
                false
            }
        }
    }

    private fun closeClientLocked() {
        isConnected = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun awaitClientInternal(timeoutMs: Int, source: String): Boolean {
        val server = serverSocket
        if (!serverRunning || server == null) {
            CLogUtils.w(TAG, "$source serverSocket为空或未运行")
            return false
        }
        return try {
            server.soTimeout = timeoutMs
            CLogUtils.d(TAG, "$source 等待客户端连接, timeoutMs=$timeoutMs")
            val client = server.accept()
            client.tcpNoDelay = true
            client.keepAlive = true
            closeClientLocked()
            socket = client
            input = DataInputStream(client.getInputStream())
            output = DataOutputStream(client.getOutputStream())
            isConnected = true
            CLogUtils.i(TAG, "$source 客户端已连接")
            true
        } catch (e: SocketTimeoutException) {
            CLogUtils.w(TAG, "$source 等待超时: ${e.message}")
            false
        } catch (e: Exception) {
            CLogUtils.e(TAG, "$source 连接失败: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    /**
     * 读取帧
     */
    private fun readFrame(): Frame? {
        return try {
            val magic = input?.readByte() ?: return null
            if (magic != FRAME_MAGIC) {
                CLogUtils.e(TAG, "readFrame() 无效的magic: 0x${String.format("%02X", magic)}")
                throw IOException("Invalid magic: $magic")
            }

            val type = input!!.readByte()
            val length = input!!.readInt()
            if (length < 0 || length > MAX_PAYLOAD_LEN) {
                CLogUtils.e(TAG, "readFrame() 帧长非法: $length (上限 $MAX_PAYLOAD_LEN), 中断连接")
                throw IOException("invalid frame length: $length")
            }
            val payload = ByteArray(length)
            input!!.readFully(payload)

            CLogUtils.v(TAG, "readFrame() 收到帧: type=0x${String.format("%02X", type)}, length=$length")
            
            // 对于事件类型，输出更详细的日志
            if (type == MSG_BINDER_EVENT) {
                CLogUtils.d(TAG, "readFrame() Binder事件: payloadSize=$length, payload=${CLogUtils.formatBytes(payload, 32)}")
            } else if (type == MSG_ERROR) {
                CLogUtils.w(TAG, "readFrame() 收到错误帧: payload=${CLogUtils.formatBytes(payload)}")
            }

            Frame(type, payload)
        } catch (e: Exception) {
            if (isConnected) {
                CLogUtils.e(TAG, "readFrame() 读取失败: ${e.message}")
            }
            null
        }
    }

    /**
     * 获取事件流
     */
    fun eventFlow(): Flow<Frame> = flow {
        CLogUtils.i(TAG, "eventFlow() 开始事件收集循环")
        var frameCount = 0
        while (serverRunning) {
            if (!isConnected) {
                val reconnected = awaitClientInternal(RECONNECT_ACCEPT_TIMEOUT_MS, "eventFlow()")
                if (!reconnected) {
                    // server仍在运行时继续等待重连
                    continue
                }
            }
            val frame = readFrame()
            if (frame != null) {
                frameCount++
                if (frameCount % 100 == 0) {
                    CLogUtils.d(TAG, "eventFlow() 已收集 $frameCount 个帧")
                }
                emit(frame)
            } else {
                CLogUtils.w(TAG, "eventFlow() readFrame返回null, 连接断开，等待重连")
                closeClientLocked()
            }
        }
        CLogUtils.i(TAG, "eventFlow() 事件收集结束, 共收集 $frameCount 个帧")
    }.flowOn(Dispatchers.IO)

    /**
     * 帧数据类
     */
    data class Frame(
        val type: Byte,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Frame
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}
