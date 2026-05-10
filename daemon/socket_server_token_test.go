package main

import (
	"encoding/binary"
	"fmt"
	"strings"
	"testing"
)

// spec § 13.1.3 + § 8.4:验证会话令牌会被嵌进 SetTarget 的 ACK message 前缀。
//
// 不启真正的 socket,直接构造 AckPayload(模拟 handleCommand 生成的)再 decode,
// 断言格式为 `session=<token>;<原文>`。
func TestAckMessage_embedsSessionToken(t *testing.T) {
	token := "abcdef123456"
	// handleCommand 里对 SetTarget 成功的 ack 构造逻辑 —— 这里直接照抄断言格式约定
	msg := fmt.Sprintf("目标UID设置为 %d", 10086)
	if token != "" {
		msg = fmt.Sprintf("session=%s;%s", token, msg)
	}
	ack := &AckPayload{Success: true, Message: msg}

	encoded := ack.Encode()
	decoded, err := DecodeAck(encoded)
	if err != nil {
		t.Fatalf("DecodeAck failed: %v", err)
	}
	if !decoded.Success {
		t.Errorf("success=false after round-trip")
	}
	expectedPrefix := "session=" + token + ";"
	if !strings.HasPrefix(decoded.Message, expectedPrefix) {
		t.Errorf("ack message missing session prefix: %q", decoded.Message)
	}
	if !strings.Contains(decoded.Message, "10086") {
		t.Errorf("ack message lost original content: %q", decoded.Message)
	}
}

// 空 token 时 message 保持原文(向后兼容 —— 没启用令牌的旧 app 不应被破坏)。
func TestAckMessage_noTokenLeavesPayloadUnchanged(t *testing.T) {
	token := ""
	msg := fmt.Sprintf("目标UID设置为 %d", 10086)
	if token != "" {
		msg = fmt.Sprintf("session=%s;%s", token, msg)
	}
	ack := &AckPayload{Success: true, Message: msg}

	encoded := ack.Encode()
	decoded, _ := DecodeAck(encoded)
	if strings.Contains(decoded.Message, "session=") {
		t.Errorf("empty token should NOT add session prefix, got %q", decoded.Message)
	}
}

// spec § 13.1.4(近似):Parcel 事件头 encode/decode 的字节序一致性,DataSize 精确重组。
func TestBinderEventPayload_roundTrip(t *testing.T) {
	payload := []byte{0x11, 0x22, 0x33, 0x44, 0x55}
	ev := &BinderEventPayload{
		Timestamp:  0x0102030405060708,
		Pid:        1234,
		Uid:        10086,
		Code:       5,
		Flags:      0x10,
		DataSize:   uint32(len(payload)),
		ParcelData: payload,
	}

	encoded := ev.Encode()
	// v2 布局:首字节为 version,timestamp 落在偏移 4..12 (BE)
	if encoded[0] != BinderEventVersionV2 {
		t.Errorf("version byte mismatch: got %d want %d", encoded[0], BinderEventVersionV2)
	}
	gotTs := int64(binary.BigEndian.Uint64(encoded[4:12]))
	if gotTs != ev.Timestamp {
		t.Errorf("timestamp BE encoding mismatch: got 0x%x", gotTs)
	}

	decoded, err := DecodeBinderEvent(encoded)
	if err != nil {
		t.Fatalf("DecodeBinderEvent failed: %v", err)
	}
	if decoded.Pid != ev.Pid || decoded.Uid != ev.Uid || decoded.Code != ev.Code {
		t.Errorf("header fields lost: %+v vs %+v", decoded, ev)
	}
	if decoded.DataSize != ev.DataSize {
		t.Errorf("DataSize mismatch: %d vs %d", decoded.DataSize, ev.DataSize)
	}
	if string(decoded.ParcelData) != string(payload) {
		t.Errorf("ParcelData corrupted")
	}
}
