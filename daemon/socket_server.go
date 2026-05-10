package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// 默认服务端地址（App 侧监听）
const DefaultListenAddr = "127.0.0.1:47291"

const (
	// 初始连接给 App 5 秒窗口起 server,容忍 App 启动时序抖动(原值 3×300ms 太紧)
	connectRetryCount   = 10
	connectRetryDelay   = 500 * time.Millisecond
	reconnectRetryCount = 3
	reconnectRetryDelay = 5 * time.Second
	connectTimeout      = 1500 * time.Millisecond
	// 每累计这么多丢弃汇总输出一次,避免每丢一次就 log 一次
	dropLogThreshold = 100
)

// SocketClient TCP客户端（连接 App Server）
type SocketClient struct {
	serverAddr string
	conn       net.Conn
	logger     *log.Logger

	// 事件通道
	eventChan chan *BinderEventPayload

	// spec § 8.4:会话令牌。daemon 在 SetTarget ACK 的 message 字段里把它原样回传,
	// App 校验通过才进入监控页。空字符串表示该次启动未启用会话校验。
	sessionToken string

	// 命令处理回调
	onSetTarget       func(uid uint32) error
	onPause           func() error
	onResume          func() error
	onShutdown        func() error
	onDisconnectFatal func(error)

	// 状态
	running          bool
	paused           atomic.Bool // readLoop 写、broadcastEvents 读;原 bool 有数据竞争
	reconnecting     bool
	eventLoopStarted bool

	mu       sync.Mutex
	writeMu  sync.Mutex // 串行化 WriteFrame,防止 header/payload 交错
	stopCh   chan struct{}
	stopOnce sync.Once

	// 通道满时累计丢弃的事件数(背压指标)
	droppedCount atomic.Uint64
}

// safeWriteFrame 串行化协议帧写入,防止多 goroutine 同时写 conn 导致帧错乱
func (s *SocketClient) safeWriteFrame(conn net.Conn, msgType uint8, payload []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return WriteFrame(conn, msgType, payload)
}

// NewSocketClient 创建TCP客户端
func NewSocketClient(serverAddr string, logger *log.Logger) *SocketClient {
	if serverAddr == "" {
		serverAddr = DefaultListenAddr
	}
	return &SocketClient{
		serverAddr: serverAddr,
		logger:     logger,
		eventChan:  make(chan *BinderEventPayload, 1000), // 缓冲1000个事件
		stopCh:     make(chan struct{}),
		running:    false,
	}
}

// SetHandlers 设置命令处理回调
func (s *SocketClient) SetHandlers(
	onSetTarget func(uid uint32) error,
	onPause func() error,
	onResume func() error,
	onShutdown func() error,
) {
	s.onSetTarget = onSetTarget
	s.onPause = onPause
	s.onResume = onResume
	s.onShutdown = onShutdown
}

// SetDisconnectHandler 设置断连且重连失败时的回调
func (s *SocketClient) SetDisconnectHandler(onDisconnectFatal func(error)) {
	s.onDisconnectFatal = onDisconnectFatal
}

// SetSessionToken 设置会话令牌(spec § 8.4)。在 SetTarget 的 ACK message 字段里回传,
// 让 App 端可以验证 "这次连接的对端确实是我刚启动的 daemon"。
func (s *SocketClient) SetSessionToken(token string) {
	s.sessionToken = token
}

// Connect 连接到App Server（3次失败即返回错误）
func (s *SocketClient) Connect() error {
	conn, err := s.connectWithRetry(connectRetryCount, connectRetryDelay, "TCP连接失败")
	if err != nil {
		return err
	}

	s.mu.Lock()
	s.conn = conn
	s.running = true
	s.reconnecting = false
	startEventLoop := !s.eventLoopStarted
	if startEventLoop {
		s.eventLoopStarted = true
	}
	s.mu.Unlock()

	s.logger.Printf("TCP连接成功: %s", s.serverAddr)
	if startEventLoop {
		go s.broadcastEvents()
	}
	go s.readLoop(conn)
	return nil
}

// Stop 停止客户端
func (s *SocketClient) Stop() error {
	s.mu.Lock()
	s.running = false
	s.reconnecting = false
	conn := s.conn
	s.conn = nil
	s.mu.Unlock()

	if conn != nil {
		_ = conn.Close()
	}

	s.stopOnce.Do(func() {
		close(s.stopCh)
	})

	s.logger.Println("TCP客户端已停止")
	return nil
}

// SendEvent 发送事件
func (s *SocketClient) SendEvent(event *BinderEventPayload) {
	if s.paused.Load() {
		return
	}

	if !s.isRunning() {
		return
	}

	// 非阻塞发送
	select {
	case s.eventChan <- event:
	default:
		// 通道满了，丢弃事件;按阈值汇总 log,避免高速率下日志自身成瓶颈
		count := s.droppedCount.Add(1)
		if count%dropLogThreshold == 0 {
			s.logger.Printf("事件通道已满,累计丢弃 %d 条事件 (eventChan cap=%d)", count, cap(s.eventChan))
		}
	}
}

// readLoop 读取App命令
func (s *SocketClient) readLoop(conn net.Conn) {
	for s.isRunning() {
		frame, err := ReadFrame(conn)
		if err != nil {
			if err != io.EOF && s.isRunning() {
				s.logger.Printf("读取帧失败: %v", err)
			}
			s.handleConnectionLoss(conn, err)
			return
		}
		s.handleCommand(conn, frame)
	}
}

// handleCommand 处理客户端命令
func (s *SocketClient) handleCommand(conn net.Conn, frame *Frame) {
	s.logger.Printf("收到命令: %s", MsgTypeName(frame.Type))

	var ackPayload *AckPayload
	var err error

	switch frame.Type {
	case MsgTypeSetTarget:
		payload, decErr := DecodeSetTarget(frame.Payload)
		if decErr != nil {
			err = decErr
		} else if s.onSetTarget != nil {
			err = s.onSetTarget(payload.Uid)
		}
		if err == nil {
			// 把会话令牌 + 协议版本能力声明嵌进 message 字段(spec § 6.2 / § 8.4)。
			// 格式:
			//   有 token:  `session=<token>;proto=2;cap=is_reply,binder_dev,target_ref;<原文>`
			//   无 token:  `proto=2;cap=is_reply,binder_dev,target_ref;<原文>`
			// 关键约束:有 token 时 session=<token>; 必须排在最前,App 端校验靠
			// startsWith("session=<token>;") 命中。proto/cap 段紧随其后,App 端解析时
			// 按 ';' 分段读 KV;旧 App 只读 success 位,会忽略前缀,仍能进入监控。
			msg := fmt.Sprintf("目标UID设置为 %d", payload.Uid)
			const protoCap = "proto=2;cap=is_reply,binder_dev,target_ref"
			if s.sessionToken != "" {
				msg = fmt.Sprintf("session=%s;%s;%s", s.sessionToken, protoCap, msg)
			} else {
				msg = fmt.Sprintf("%s;%s", protoCap, msg)
			}
			ackPayload = &AckPayload{Success: true, Message: msg}
		}

	case MsgTypePause:
		s.paused.Store(true)
		if s.onPause != nil {
			err = s.onPause()
		}
		if err == nil {
			ackPayload = &AckPayload{Success: true, Message: "事件推送已暂停"}
		}

	case MsgTypeResume:
		s.paused.Store(false)
		if s.onResume != nil {
			err = s.onResume()
		}
		if err == nil {
			ackPayload = &AckPayload{Success: true, Message: "事件推送已恢复"}
		}

	case MsgTypeShutdown:
		ackPayload = &AckPayload{Success: true, Message: "正在关闭..."}
		s.sendAck(conn, ackPayload)
		if s.onShutdown != nil {
			s.onShutdown()
		}
		return

	case MsgTypePing:
		// 直接回复Pong
		s.safeWriteFrame(conn, MsgTypePong, nil)
		return

	default:
		err = fmt.Errorf("未知命令类型: 0x%02x", frame.Type)
	}

	if err != nil {
		s.sendError(conn, 1, err.Error())
	} else if ackPayload != nil {
		s.sendAck(conn, ackPayload)
	}
}

// sendAck 发送确认响应
func (s *SocketClient) sendAck(conn net.Conn, ack *AckPayload) {
	if err := s.safeWriteFrame(conn, MsgTypeAck, ack.Encode()); err != nil {
		s.logger.Printf("发送ACK失败: %v", err)
	}
}

// sendError 发送错误响应
func (s *SocketClient) sendError(conn net.Conn, code uint32, message string) {
	errPayload := &ErrorPayload{Code: code, Message: message}
	if err := s.safeWriteFrame(conn, MsgTypeError, errPayload.Encode()); err != nil {
		s.logger.Printf("发送错误响应失败: %v", err)
	}
}

// broadcastEvents 推送事件到App（单协程，连接断开时触发重连）
func (s *SocketClient) broadcastEvents() {
	for {
		select {
		case <-s.stopCh:
			return
		case event := <-s.eventChan:
			if event == nil {
				continue
			}
			if s.paused.Load() {
				continue
			}

			conn := s.currentConn()
			if conn == nil {
				// 重连窗口期间没有可用连接，丢弃事件
				continue
			}

			payload := event.Encode()
			if err := s.safeWriteFrame(conn, MsgTypeBinderEvent, payload); err != nil {
				s.logger.Printf("发送事件失败: %v", err)
				s.handleConnectionLoss(conn, err)
			}
		}
	}
}

func (s *SocketClient) connectWithRetry(maxAttempts int, retryDelay time.Duration, logPrefix string) (net.Conn, error) {
	var lastErr error
	for i := 1; i <= maxAttempts; i++ {
		dialer := net.Dialer{
			Timeout:   connectTimeout,
			KeepAlive: 15 * time.Second,
		}
		conn, err := dialer.Dial("tcp", s.serverAddr)
		if err == nil {
			if tcpConn, ok := conn.(*net.TCPConn); ok {
				_ = tcpConn.SetKeepAlive(true)
				_ = tcpConn.SetKeepAlivePeriod(15 * time.Second)
			}
			return conn, nil
		}
		lastErr = err
		s.logger.Printf("%s(%d/%d): %v", logPrefix, i, maxAttempts, err)
		if i < maxAttempts {
			time.Sleep(retryDelay)
		}
	}
	return nil, fmt.Errorf("connect to %s failed after %d attempts: %w", s.serverAddr, maxAttempts, lastErr)
}

func (s *SocketClient) handleConnectionLoss(failedConn net.Conn, cause error) {
	s.mu.Lock()
	if !s.running || s.conn != failedConn {
		s.mu.Unlock()
		return
	}
	if s.reconnecting {
		s.mu.Unlock()
		return
	}
	s.reconnecting = true
	s.conn = nil
	s.mu.Unlock()

	if failedConn != nil {
		_ = failedConn.Close()
	}

	go s.reconnectLoop(cause)
}

func (s *SocketClient) reconnectLoop(initialErr error) {
	s.logger.Printf("连接已断开，开始重连（最多%d次，每次间隔%v）: %v", reconnectRetryCount, reconnectRetryDelay, initialErr)

	var lastErr error = initialErr
	for i := 1; i <= reconnectRetryCount; i++ {
		if !s.isRunning() {
			return
		}

		dialer := net.Dialer{
			Timeout:   connectTimeout,
			KeepAlive: 15 * time.Second,
		}
		conn, err := dialer.Dial("tcp", s.serverAddr)
		if err == nil {
			if tcpConn, ok := conn.(*net.TCPConn); ok {
				_ = tcpConn.SetKeepAlive(true)
				_ = tcpConn.SetKeepAlivePeriod(15 * time.Second)
			}
			s.mu.Lock()
			if !s.running {
				s.mu.Unlock()
				_ = conn.Close()
				return
			}
			s.conn = conn
			s.reconnecting = false
			s.mu.Unlock()

			s.logger.Printf("TCP重连成功(%d/%d): %s", i, reconnectRetryCount, s.serverAddr)
			go s.readLoop(conn)
			return
		}

		lastErr = err
		s.logger.Printf("TCP重连失败(%d/%d): %v", i, reconnectRetryCount, err)
		if i < reconnectRetryCount {
			time.Sleep(reconnectRetryDelay)
		}
	}

	s.mu.Lock()
	if !s.running {
		s.reconnecting = false
		s.mu.Unlock()
		return
	}
	s.reconnecting = false
	s.running = false
	s.mu.Unlock()

	fatalErr := fmt.Errorf("TCP重连失败，已重试%d次（间隔%v）: %w", reconnectRetryCount, reconnectRetryDelay, lastErr)
	s.logger.Println(fatalErr.Error())
	if s.onDisconnectFatal != nil {
		s.onDisconnectFatal(fatalErr)
	}
}

func (s *SocketClient) isRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

func (s *SocketClient) currentConn() net.Conn {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conn
}
