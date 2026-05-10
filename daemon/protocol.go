package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"sync/atomic"
	"time"
)

// =====================================================
// Unix Socket 通信协议定义
// =====================================================
//
// 帧格式 (Big Endian):
// +--------+--------+--------+--------+--------+--------+...+--------+
// | Magic  | Type   |     Length (4 bytes)            |   Payload   |
// +--------+--------+--------+--------+--------+--------+...+--------+
//  1 byte   1 byte          4 bytes                    Length bytes
//
// Magic:   0xBD (Binder Daemon 标识)
// Type:    消息类型
// Length:  Payload长度 (不包括帧头6字节)
// Payload: 具体数据
//
// =====================================================

// 协议常量
const (
	FrameMagic     byte = 0xBD // 帧魔数
	FrameHeaderLen      = 6    // 帧头长度: Magic(1) + Type(1) + Length(4)
	MaxPayloadLen       = 1 << 20 // 最大负载长度: 1MB
)

// 消息类型定义
const (
	// 命令类型 (App -> btrace)
	MsgTypeSetTarget uint8 = 0x01 // 设置目标UID
	MsgTypePause     uint8 = 0x02 // 暂停事件推送
	MsgTypeResume    uint8 = 0x03 // 恢复事件推送
	MsgTypeShutdown  uint8 = 0x04 // 关闭btrace
	MsgTypePing      uint8 = 0x05 // 心跳检测

	// 事件类型 (btrace -> App)
	MsgTypeBinderEvent uint8 = 0x10 // Binder事件
	MsgTypeAck         uint8 = 0x11 // 命令确认
	MsgTypeError       uint8 = 0x12 // 错误消息
	MsgTypePong        uint8 = 0x13 // 心跳响应
)

// 协议错误定义
var (
	ErrInvalidMagic   = errors.New("invalid frame magic")
	ErrPayloadTooLong = errors.New("payload too long")
	ErrInvalidFrame   = errors.New("invalid frame")
)

// =====================================================
// 帧读写
// =====================================================

// Frame 表示一个协议帧
type Frame struct {
	Type    uint8
	Payload []byte
}

// WriteFrame 将帧写入writer
func WriteFrame(w io.Writer, msgType uint8, payload []byte) error {
	if len(payload) > MaxPayloadLen {
		return ErrPayloadTooLong
	}

	// 写入帧头
	header := make([]byte, FrameHeaderLen)
	header[0] = FrameMagic
	header[1] = msgType
	binary.BigEndian.PutUint32(header[2:], uint32(len(payload)))

	if _, err := w.Write(header); err != nil {
		return fmt.Errorf("write header: %w", err)
	}

	// 写入负载
	if len(payload) > 0 {
		if _, err := w.Write(payload); err != nil {
			return fmt.Errorf("write payload: %w", err)
		}
	}

	return nil
}

// ReadFrame 从reader读取一个帧
func ReadFrame(r io.Reader) (*Frame, error) {
	// 读取帧头
	header := make([]byte, FrameHeaderLen)
	if _, err := io.ReadFull(r, header); err != nil {
		return nil, err
	}

	// 验证魔数
	if header[0] != FrameMagic {
		return nil, ErrInvalidMagic
	}

	msgType := header[1]
	payloadLen := binary.BigEndian.Uint32(header[2:])

	if payloadLen > MaxPayloadLen {
		return nil, ErrPayloadTooLong
	}

	// 读取负载
	payload := make([]byte, payloadLen)
	if payloadLen > 0 {
		if _, err := io.ReadFull(r, payload); err != nil {
			return nil, err
		}
	}

	return &Frame{
		Type:    msgType,
		Payload: payload,
	}, nil
}

// =====================================================
// 事件负载定义
// =====================================================

// BinderEventPayload Binder事件数据结构 (协议 v2,spec § 6.2)
//
// 二进制布局 (Big Endian, 头部 60 字节):
//
//	off  len  field
//	 0    1B  version          = 2
//	 1    1B  ext_flags        bit 0 = HAS_STACK_TLV(spec 2026-05-03 § 4.4.1),其它 bit 保留
//	 2    2B  reserved0        = 0
//	 4    8B  timestamp_ns
//	12    4B  pid              (sender pid)
//	16    4B  uid              (sender uid)
//	20    4B  code
//	24    4B  flags
//	28    4B  data_size
//	32    1B  is_reply
//	33    1B  binder_dev       0=UNKNOWN 1=BINDER 2=HWBINDER 3=VNDBINDER
//	34    1B  target_kind      0=UNKNOWN 1=HANDLE 2=PTR
//	35    1B  reserved1
//	36    4B  to_pid
//	40    4B  to_uid
//	44    8B  target_ref
//	52    8B  pair_id
//	60    ... parcel data (data_size bytes)
//	60+data_size  [可选 TLV 段,仅当 ext_flags & 0x01]:
//	   1B  tlv_type     = 0x01 (STACK_TRACE)
//	   4B  tlv_length   (BE,后续 payload 字节数)
//	   payload(StackTrace 序列化,见 § 4.4.2)
//
// **不 bump 版本号**:ext_flags + TLV 完全向后兼容(spec § 4.4.1)。
type BinderEventPayload struct {
	Timestamp  int64       // 时间戳 (Unix纳秒)
	Pid        uint32      // 发送方 PID
	Uid        uint32      // 发送方 UID
	Code       uint32      // 方法Code
	Flags      uint32      // Binder Flags
	DataSize   uint32      // Parcel数据大小
	IsReply    uint8       // 1=reply 帧, 0=request 帧
	BinderDev  uint8       // 来源 binder 设备(BINDER/HWBINDER/VNDBINDER/UNKNOWN)
	TargetKind uint8       // target_ref 是 handle 还是 ptr;本期 stub UNKNOWN
	ToPid      uint32      // 接收方 PID;本期 stub 0
	ToUid      uint32      // 接收方 UID;本期 stub 0
	TargetRef  uint64      // tr->target 原始 union 值
	PairId     uint64      // request/reply 配对键;本期 stub 0
	ParcelData []byte      // Parcel原始数据
	StackTrace *StackTrace // 调用栈(spec 2026-05-03 § 4.4):无栈时 nil,有栈时序列化为 STACK_TRACE TLV
}

// 协议版本与头部长度常量 (spec § 6.2)
const (
	BinderEventVersionV2 byte = 2
	BinderEventHeaderLen      = 60
)

// ext_flags 位定义(spec 2026-05-03 § 4.4.1)
const (
	ExtFlagHasStackTLV byte = 0x01 // bit 0:事件后跟 STACK_TRACE TLV
)

// TLV 类型(spec § 4.4.2)
const (
	TLVTypeStackTrace byte = 0x01
)

const tlvHeaderLen = 5 // 1B type + 4B length BE

// Encode 将事件编码为 v2 字节数组(可选携带 STACK_TRACE TLV,spec 2026-05-03 § 4.4.1)
func (e *BinderEventPayload) Encode() []byte {
	var stackTLV []byte
	var extFlags byte = 0
	if e.StackTrace != nil {
		stackTLV = EncodeStackTraceTLV(e.StackTrace)
		extFlags |= ExtFlagHasStackTLV
	}

	totalLen := BinderEventHeaderLen + len(e.ParcelData)
	if len(stackTLV) > 0 {
		totalLen += tlvHeaderLen + len(stackTLV)
	}
	buf := make([]byte, totalLen)

	buf[0] = BinderEventVersionV2
	buf[1] = extFlags
	// buf[2..4] reserved0 已为 0

	binary.BigEndian.PutUint64(buf[4:], uint64(e.Timestamp))
	binary.BigEndian.PutUint32(buf[12:], e.Pid)
	binary.BigEndian.PutUint32(buf[16:], e.Uid)
	binary.BigEndian.PutUint32(buf[20:], e.Code)
	binary.BigEndian.PutUint32(buf[24:], e.Flags)
	binary.BigEndian.PutUint32(buf[28:], uint32(len(e.ParcelData)))

	buf[32] = e.IsReply
	buf[33] = e.BinderDev
	buf[34] = e.TargetKind
	buf[35] = 0 // reserved1

	binary.BigEndian.PutUint32(buf[36:], e.ToPid)
	binary.BigEndian.PutUint32(buf[40:], e.ToUid)
	binary.BigEndian.PutUint64(buf[44:], e.TargetRef)
	binary.BigEndian.PutUint64(buf[52:], e.PairId)

	copy(buf[BinderEventHeaderLen:], e.ParcelData)

	if len(stackTLV) > 0 {
		off := BinderEventHeaderLen + len(e.ParcelData)
		buf[off] = TLVTypeStackTrace
		binary.BigEndian.PutUint32(buf[off+1:], uint32(len(stackTLV)))
		copy(buf[off+tlvHeaderLen:], stackTLV)
	}

	return buf
}

// ErrUnsupportedEventVersion 表示 payload 首字节 != v2(daemon 已停止支持 v1)
var ErrUnsupportedEventVersion = errors.New("unsupported binder event version")

// ErrTLVGuardFailOpen 是 G1/G2 静默 sentinel:base 头自身坏掉时返回此 err,
// 调用方按 spec § 4.4.4 "绝不报错日志(避免日志洪流)"语义**静默忽略**,只
// 通过 tlvGuard 静默计数。区别于 ErrInvalidFrame:后者用于其它真错误(version
// 不对、命令 payload 长度不够等),会被调用方 log.Printf;前者必须不写任何
// 日志,与 G4/G5 fail-open(返回事件 + stackTrace=nil)一起构成完整 5 级 fail-open。
var ErrTLVGuardFailOpen = errors.New("tlv guard fail-open (silent)")

// TLVGuardCounters 5 级 fail-open guard 静默计数(spec § 4.4.4),用于 telemetry。
// 每级失败一律返回 stackTrace=null,**绝不**抛异常 / 报错日志。
type TLVGuardCounters struct {
	G1BaseHeaderTooShort   atomic.Uint64 // payloadLen < 60
	G2ParcelTruncated      atomic.Uint64 // payloadLen < 60+data_size
	G4TLVHeaderTruncated   atomic.Uint64 // payloadLen < baseEnd+5
	G5TLVPayloadOverflow   atomic.Uint64 // tlv_length > payloadLen-baseEnd-5
}

// 全局 TLV guard 计数器(daemon 1Hz 上报到 socket telemetry)
var tlvGuard TLVGuardCounters

// TLVGuardSnapshot 取一份快照(给 socket 1Hz 上报)
func TLVGuardSnapshot() (g1, g2, g4, g5 uint64) {
	return tlvGuard.G1BaseHeaderTooShort.Load(),
		tlvGuard.G2ParcelTruncated.Load(),
		tlvGuard.G4TLVHeaderTruncated.Load(),
		tlvGuard.G5TLVPayloadOverflow.Load()
}

// DecodeBinderEvent 从 v2 字节数组解码事件;首字节非 2 直接拒绝。
//
// 5 级 fail-open guard 链(spec § 4.4.4,daemon Go 与 app Kotlin 必须完全一致):
//   Guard 1: payloadLen >= 60          (base 头本身完整)
//   Guard 2: payloadLen >= 60+data_size (ParcelData 完整)
//   Guard 3: ext_flags & 0x01           (HAS_STACK_TLV 位)
//   Guard 4: payloadLen >= baseEnd+5    (TLV header 完整)
//   Guard 5: tlv_length <= payloadLen - baseEnd - 5 (TLV payload 不越界)
//
// G1/G2 失败 → 返回 (nil, ErrTLVGuardFailOpen) 静默 sentinel,调用方按
//             spec § 4.4.4 "绝不报错日志"语义不写日志,只 tlvGuard 静默计数;
// G3 = 0 → 正常返回 stackTrace=nil(无 TLV);
// G4/G5 失败 → fail-open 返回事件 + stackTrace=nil,**不抛错**(只在 telemetry 静默计数)。
func DecodeBinderEvent(data []byte) (*BinderEventPayload, error) {
	payloadLen := len(data)

	// === Guard 1:base 头本身完整(payloadLen >= 60)===
	// 注意:必须先校验 payloadLen >= 60,**再**读 ext_flags / data_size,否则越界 UB。
	if payloadLen < BinderEventHeaderLen {
		tlvGuard.G1BaseHeaderTooShort.Add(1)
		return nil, ErrTLVGuardFailOpen // spec § 4.4.4:静默 fail-open
	}
	if data[0] != BinderEventVersionV2 {
		return nil, ErrUnsupportedEventVersion
	}

	extFlags := data[1]
	dataSize := binary.BigEndian.Uint32(data[28:])
	baseEnd := BinderEventHeaderLen + int(dataSize)

	// === Guard 2:ParcelData 完整(payloadLen >= 60 + data_size)===
	if payloadLen < baseEnd {
		tlvGuard.G2ParcelTruncated.Add(1)
		return nil, ErrTLVGuardFailOpen // spec § 4.4.4:静默 fail-open
	}

	ev := &BinderEventPayload{
		Timestamp:  int64(binary.BigEndian.Uint64(data[4:])),
		Pid:        binary.BigEndian.Uint32(data[12:]),
		Uid:        binary.BigEndian.Uint32(data[16:]),
		Code:       binary.BigEndian.Uint32(data[20:]),
		Flags:      binary.BigEndian.Uint32(data[24:]),
		DataSize:   dataSize,
		IsReply:    data[32],
		BinderDev:  data[33],
		TargetKind: data[34],
		ToPid:      binary.BigEndian.Uint32(data[36:]),
		ToUid:      binary.BigEndian.Uint32(data[40:]),
		TargetRef:  binary.BigEndian.Uint64(data[44:]),
		PairId:     binary.BigEndian.Uint64(data[52:]),
		ParcelData: data[BinderEventHeaderLen:baseEnd],
	}

	// === Guard 3:HAS_STACK_TLV 位置位 ===
	if extFlags&ExtFlagHasStackTLV == 0 {
		return ev, nil // 无 TLV,正常路径
	}

	// === Guard 4:TLV header 5B 完整(1B type + 4B length BE)===
	if payloadLen < baseEnd+tlvHeaderLen {
		tlvGuard.G4TLVHeaderTruncated.Add(1)
		return ev, nil // fail-open:返回事件 + stackTrace=nil
	}

	tlvType := data[baseEnd]
	tlvLength := binary.BigEndian.Uint32(data[baseEnd+1:])

	if tlvType != TLVTypeStackTrace {
		// 未知 TLV 类型 → 跳过(向前兼容:未来可能加 0x02 = JAVA_STACK 等)
		return ev, nil
	}

	// === Guard 5:TLV payload 不越界(tlv_length <= payloadLen - baseEnd - 5)===
	maxTlvLen := payloadLen - baseEnd - tlvHeaderLen
	if int(tlvLength) > maxTlvLen {
		tlvGuard.G5TLVPayloadOverflow.Add(1)
		return ev, nil // fail-open
	}

	// === 全部 guard 通过,安全解析 TLV payload ===
	tlvStart := baseEnd + tlvHeaderLen
	ev.StackTrace = DecodeStackTraceTLV(data[tlvStart : tlvStart+int(tlvLength)])
	return ev, nil
}

// =====================================================
// 命令负载定义
// =====================================================

// SetTargetPayload 设置目标UID的负载
// 二进制格式:
// +------+
// | UID  |
// +------+
//  4 bytes
type SetTargetPayload struct {
	Uid uint32
}

// Encode 编码
func (p *SetTargetPayload) Encode() []byte {
	buf := make([]byte, 4)
	binary.BigEndian.PutUint32(buf, p.Uid)
	return buf
}

// DecodeSetTarget 解码
func DecodeSetTarget(data []byte) (*SetTargetPayload, error) {
	if len(data) < 4 {
		return nil, ErrInvalidFrame
	}
	return &SetTargetPayload{
		Uid: binary.BigEndian.Uint32(data),
	}, nil
}

// =====================================================
// 响应负载定义
// =====================================================

// AckPayload 确认响应负载
type AckPayload struct {
	Success bool
	Message string
}

// Encode 编码
func (p *AckPayload) Encode() []byte {
	msgBytes := []byte(p.Message)
	buf := make([]byte, 1+4+len(msgBytes))
	
	if p.Success {
		buf[0] = 1
	} else {
		buf[0] = 0
	}
	binary.BigEndian.PutUint32(buf[1:], uint32(len(msgBytes)))
	copy(buf[5:], msgBytes)
	
	return buf
}

// DecodeAck 解码确认响应
func DecodeAck(data []byte) (*AckPayload, error) {
	if len(data) < 5 {
		return nil, ErrInvalidFrame
	}
	
	success := data[0] == 1
	msgLen := binary.BigEndian.Uint32(data[1:])
	
	if len(data) < 5+int(msgLen) {
		return nil, ErrInvalidFrame
	}
	
	return &AckPayload{
		Success: success,
		Message: string(data[5 : 5+msgLen]),
	}, nil
}

// ErrorPayload 错误响应负载
type ErrorPayload struct {
	Code    uint32
	Message string
}

// Encode 编码
func (p *ErrorPayload) Encode() []byte {
	msgBytes := []byte(p.Message)
	buf := make([]byte, 4+4+len(msgBytes))
	
	binary.BigEndian.PutUint32(buf[0:], p.Code)
	binary.BigEndian.PutUint32(buf[4:], uint32(len(msgBytes)))
	copy(buf[8:], msgBytes)
	
	return buf
}

// DecodeError 解码错误响应
func DecodeError(data []byte) (*ErrorPayload, error) {
	if len(data) < 8 {
		return nil, ErrInvalidFrame
	}
	
	code := binary.BigEndian.Uint32(data[0:])
	msgLen := binary.BigEndian.Uint32(data[4:])
	
	if len(data) < 8+int(msgLen) {
		return nil, ErrInvalidFrame
	}
	
	return &ErrorPayload{
		Code:    code,
		Message: string(data[8 : 8+msgLen]),
	}, nil
}

// =====================================================
// 辅助函数
// =====================================================

// NowTimestamp 获取当前时间戳（纳秒）
func NowTimestamp() int64 {
	return time.Now().UnixNano()
}

// MsgTypeName 获取消息类型名称
func MsgTypeName(t uint8) string {
	switch t {
	case MsgTypeSetTarget:
		return "SetTarget"
	case MsgTypePause:
		return "Pause"
	case MsgTypeResume:
		return "Resume"
	case MsgTypeShutdown:
		return "Shutdown"
	case MsgTypePing:
		return "Ping"
	case MsgTypeBinderEvent:
		return "BinderEvent"
	case MsgTypeAck:
		return "Ack"
	case MsgTypeError:
		return "Error"
	case MsgTypePong:
		return "Pong"
	default:
		return fmt.Sprintf("Unknown(0x%02x)", t)
	}
}

// =====================================================
// StackTrace 类型(spec 2026-05-03 § 4.4.2)
// =====================================================

// StackTrace 调用栈(daemon 反符号化后)
//
// payload 布局(Big Endian):
//
//	off  len  field
//	 0    1B  quality          0=FULL 1=FP_ONLY 2=DEGRADED 3=FAILED
//	 1    1B  truncated        bit 0 = kstack 32 满,bit 1 = ustack 32 满
//	 2    2B  reserved
//	 4    2B  kframe_count
//	 6    2B  uframe_count
//	 8   ...  kframe[0..kframe_count) 数组,每帧:
//	            8B pc + 2B module_len + 2B symbol_len + 8B offset
//	            + module_string + symbol_string
//	 ...  ...  uframe[0..uframe_count) 数组,同上
//	 ...  ?B  failure_reason_len(2B)+ failure_reason(变长,quality=FAILED 时填,否则 0/空)
type StackTrace struct {
	Quality       StackQuality // 0=FULL 1=FP_ONLY 2=DEGRADED 3=FAILED
	Truncated     uint8        // bit0=kstack 满,bit1=ustack 满
	KFrames       []StackFrame
	UFrames       []StackFrame
	FailureReason string
}

// StackFrame 单帧
type StackFrame struct {
	PC     uint64
	Module string
	Symbol string
	Offset uint64
}

// EncodeStackTraceTLV 把 StackTrace 序列化为 STACK_TRACE TLV payload(不含 5B 头)
func EncodeStackTraceTLV(st *StackTrace) []byte {
	if st == nil {
		return nil
	}
	buf := make([]byte, 0, 8+(len(st.KFrames)+len(st.UFrames))*70+len(st.FailureReason)+2)
	header := make([]byte, 8)
	header[0] = uint8(st.Quality)
	header[1] = st.Truncated
	binary.BigEndian.PutUint16(header[4:], uint16(len(st.KFrames)))
	binary.BigEndian.PutUint16(header[6:], uint16(len(st.UFrames)))
	buf = append(buf, header...)

	encodeFrame := func(fr StackFrame) {
		var hdr [20]byte
		binary.BigEndian.PutUint64(hdr[0:], fr.PC)
		mb := []byte(fr.Module)
		sb := []byte(fr.Symbol)
		binary.BigEndian.PutUint16(hdr[8:], uint16(len(mb)))
		binary.BigEndian.PutUint16(hdr[10:], uint16(len(sb)))
		binary.BigEndian.PutUint64(hdr[12:], fr.Offset)
		buf = append(buf, hdr[:]...)
		buf = append(buf, mb...)
		buf = append(buf, sb...)
	}
	for _, fr := range st.KFrames {
		encodeFrame(fr)
	}
	for _, fr := range st.UFrames {
		encodeFrame(fr)
	}

	rb := []byte(st.FailureReason)
	rl := make([]byte, 2)
	binary.BigEndian.PutUint16(rl, uint16(len(rb)))
	buf = append(buf, rl...)
	buf = append(buf, rb...)

	return buf
}

// DecodeStackTraceTLV 解析 STACK_TRACE TLV payload(不含 5B 头);
// 任何越界 / 长度异常 → 返回 nil(fail-open),**不**抛错。
func DecodeStackTraceTLV(data []byte) *StackTrace {
	if len(data) < 8 {
		return nil
	}
	st := &StackTrace{
		Quality:   StackQuality(data[0]),
		Truncated: data[1],
	}
	kCount := int(binary.BigEndian.Uint16(data[4:]))
	uCount := int(binary.BigEndian.Uint16(data[6:]))
	off := 8

	parseFrames := func(count int) ([]StackFrame, bool) {
		if count == 0 {
			return nil, true
		}
		out := make([]StackFrame, 0, count)
		for i := 0; i < count; i++ {
			if off+20 > len(data) {
				return nil, false
			}
			pc := binary.BigEndian.Uint64(data[off:])
			mLen := int(binary.BigEndian.Uint16(data[off+8:]))
			sLen := int(binary.BigEndian.Uint16(data[off+10:]))
			fOff := binary.BigEndian.Uint64(data[off+12:])
			off += 20
			if off+mLen+sLen > len(data) {
				return nil, false
			}
			module := string(data[off : off+mLen])
			off += mLen
			symbol := string(data[off : off+sLen])
			off += sLen
			out = append(out, StackFrame{PC: pc, Module: module, Symbol: symbol, Offset: fOff})
		}
		return out, true
	}

	var ok bool
	st.KFrames, ok = parseFrames(kCount)
	if !ok {
		return nil
	}
	st.UFrames, ok = parseFrames(uCount)
	if !ok {
		return nil
	}
	if off+2 <= len(data) {
		rl := int(binary.BigEndian.Uint16(data[off:]))
		off += 2
		if rl > 0 && off+rl <= len(data) {
			st.FailureReason = string(data[off : off+rl])
		}
	}
	return st
}
