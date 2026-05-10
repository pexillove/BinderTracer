package main

import (
	"errors"
	"testing"
)

// spec § 6.2:v2 payload 头部 60 字节布局。Encode -> Decode 必须保字段。
func TestBinderEventPayloadRoundTripV2(t *testing.T) {
	parcel := []byte{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}
	ev := &BinderEventPayload{
		Timestamp:  0x0102030405060708,
		Pid:        12345,
		Uid:        10086,
		Code:       0x5F504E47, // _PNG (PING_TRANSACTION)
		Flags:      0x10,
		DataSize:   uint32(len(parcel)),
		IsReply:    1,
		BinderDev:  2, // HWBINDER
		TargetKind: 1, // HANDLE
		ToPid:      999,
		ToUid:      1000,
		TargetRef:  0xDEADBEEFCAFEBABE,
		PairId:     0xFEEDFACE12345678,
		ParcelData: parcel,
	}

	encoded := ev.Encode()
	if len(encoded) != BinderEventHeaderLen+len(parcel) {
		t.Fatalf("encoded length = %d, want %d", len(encoded), BinderEventHeaderLen+len(parcel))
	}
	if encoded[0] != BinderEventVersionV2 {
		t.Fatalf("first byte = %d, want version %d", encoded[0], BinderEventVersionV2)
	}

	decoded, err := DecodeBinderEvent(encoded)
	if err != nil {
		t.Fatalf("DecodeBinderEvent failed: %v", err)
	}

	if decoded.Timestamp != ev.Timestamp {
		t.Errorf("Timestamp: got 0x%x want 0x%x", decoded.Timestamp, ev.Timestamp)
	}
	if decoded.Pid != ev.Pid || decoded.Uid != ev.Uid {
		t.Errorf("Pid/Uid: got %d/%d want %d/%d", decoded.Pid, decoded.Uid, ev.Pid, ev.Uid)
	}
	if decoded.Code != ev.Code || decoded.Flags != ev.Flags {
		t.Errorf("Code/Flags: got 0x%x/0x%x want 0x%x/0x%x", decoded.Code, decoded.Flags, ev.Code, ev.Flags)
	}
	if decoded.DataSize != ev.DataSize {
		t.Errorf("DataSize: got %d want %d", decoded.DataSize, ev.DataSize)
	}
	if decoded.IsReply != ev.IsReply {
		t.Errorf("IsReply: got %d want %d", decoded.IsReply, ev.IsReply)
	}
	if decoded.BinderDev != ev.BinderDev {
		t.Errorf("BinderDev: got %d want %d", decoded.BinderDev, ev.BinderDev)
	}
	if decoded.TargetKind != ev.TargetKind {
		t.Errorf("TargetKind: got %d want %d", decoded.TargetKind, ev.TargetKind)
	}
	if decoded.ToPid != ev.ToPid || decoded.ToUid != ev.ToUid {
		t.Errorf("ToPid/ToUid: got %d/%d want %d/%d", decoded.ToPid, decoded.ToUid, ev.ToPid, ev.ToUid)
	}
	if decoded.TargetRef != ev.TargetRef {
		t.Errorf("TargetRef: got 0x%x want 0x%x", decoded.TargetRef, ev.TargetRef)
	}
	if decoded.PairId != ev.PairId {
		t.Errorf("PairId: got 0x%x want 0x%x", decoded.PairId, ev.PairId)
	}
	if string(decoded.ParcelData) != string(parcel) {
		t.Errorf("ParcelData: got %x want %x", decoded.ParcelData, parcel)
	}
}

// spec § 6.2:daemon 一次升 v2,首字节 != 2 必须返回 ErrUnsupportedEventVersion。
func TestDecodeBinderEventRejectsOldVersion(t *testing.T) {
	// 构造一个看起来合法但 version=1 的 buffer
	buf := make([]byte, BinderEventHeaderLen)
	buf[0] = 1 // 错误版本
	_, err := DecodeBinderEvent(buf)
	if err == nil {
		t.Fatal("DecodeBinderEvent should reject version=1, got nil err")
	}
	if !errors.Is(err, ErrUnsupportedEventVersion) {
		t.Errorf("expected ErrUnsupportedEventVersion, got %v", err)
	}

	// 构造一个 version=0 (零值) 的 buffer:也应被拒
	zeros := make([]byte, BinderEventHeaderLen)
	_, err = DecodeBinderEvent(zeros)
	if !errors.Is(err, ErrUnsupportedEventVersion) {
		t.Errorf("expected ErrUnsupportedEventVersion for zero buffer, got %v", err)
	}
}

// 字节布局回归:固定字段必须落在 spec 约定偏移上 (Big Endian)。
func TestBinderEventPayloadByteLayout(t *testing.T) {
	ev := &BinderEventPayload{
		Timestamp: 0x0102030405060708,
		Pid:       0x11223344,
	}
	encoded := ev.Encode()

	if encoded[0] != 2 {
		t.Errorf("offset 0 (version) = %d, want 2", encoded[0])
	}
	// 偏移 4..12: timestamp BE
	wantTs := []byte{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}
	for i, b := range wantTs {
		if encoded[4+i] != b {
			t.Errorf("offset %d (timestamp byte %d) = 0x%x, want 0x%x", 4+i, i, encoded[4+i], b)
		}
	}
	// 偏移 12..16: pid BE
	wantPid := []byte{0x11, 0x22, 0x33, 0x44}
	for i, b := range wantPid {
		if encoded[12+i] != b {
			t.Errorf("offset %d (pid byte %d) = 0x%x, want 0x%x", 12+i, i, encoded[12+i], b)
		}
	}
}
