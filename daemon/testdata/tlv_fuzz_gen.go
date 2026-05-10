//go:build ignore

// tlv_fuzz_gen.go 生成 testdata/tlv-fuzz-vectors.bin —— daemon Go 与 app Kotlin
// 共享的双端一致性 fuzz vectors(spec § 4.4.4 + § 13 D5)。
//
// 用法:
//   cd daemon/testdata && go run tlv_fuzz_gen.go
// 输出:
//   tlv-fuzz-vectors.bin
//
// 文件格式(全 BE):
//   header:  4B "TLVF" magic + 2B vector_count
//   vector:  2B size_n + 1B expect_stack_null (0/1) + 2B note_len + note + n bytes payload
//
// 两端测试读这同一个文件,逐 vector decode,断言:
//   * Go DecodeBinderEvent 返回的 (event.StackTrace == nil) == bool(expect_stack_null)
//   * Kotlin BinderEvent.fromPayload 返回的 (event?.stackTrace == null) == bool(expect_stack_null)
//
// 一致性(两端必须等价 null/非 null)由 spec § 4.4.4 BLOCKER B2 一致性约束保证。
package main

import (
	"encoding/binary"
	"fmt"
	"os"
)

type vector struct {
	note            string
	expectStackNull bool // true = 期望两端 stackTrace 都是 null
	build           func() []byte
}

const (
	hdrLen = 60
)

func baseHeader(extFlags byte, dataSize uint32) []byte {
	buf := make([]byte, hdrLen)
	buf[0] = 2 // version v2
	buf[1] = extFlags
	binary.BigEndian.PutUint64(buf[4:], 0xCAFEBABE12345678) // ts
	binary.BigEndian.PutUint32(buf[12:], 1234)              // pid
	binary.BigEndian.PutUint32(buf[16:], 10086)             // uid
	binary.BigEndian.PutUint32(buf[20:], 5)                 // code
	binary.BigEndian.PutUint32(buf[24:], 0x10)              // flags
	binary.BigEndian.PutUint32(buf[28:], dataSize)
	buf[32] = 0
	buf[33] = 1
	buf[34] = 1
	buf[35] = 0
	binary.BigEndian.PutUint32(buf[36:], 0)
	binary.BigEndian.PutUint32(buf[40:], 0)
	binary.BigEndian.PutUint64(buf[44:], 0)
	binary.BigEndian.PutUint64(buf[52:], 0)
	return buf
}

// 构造一个最小合法 STACK_TRACE TLV payload(不含 5B 头)
func minimalStackTLVPayload() []byte {
	// 8B header + 1 kframe(20B 固定 + 6B "kernel" + 12B "binder_ioctl") + 1 uframe(20B + 5B "/x.so" + 4B "main") + 2B failure_reason_len = 0
	out := make([]byte, 0, 80)
	hdr := make([]byte, 8)
	hdr[0] = 0 // FULL
	hdr[1] = 0
	binary.BigEndian.PutUint16(hdr[4:], 1) // kframe_count
	binary.BigEndian.PutUint16(hdr[6:], 1) // uframe_count
	out = append(out, hdr...)

	enc := func(pc uint64, mod, sym string, off uint64) []byte {
		fb := make([]byte, 20)
		binary.BigEndian.PutUint64(fb[0:], pc)
		binary.BigEndian.PutUint16(fb[8:], uint16(len(mod)))
		binary.BigEndian.PutUint16(fb[10:], uint16(len(sym)))
		binary.BigEndian.PutUint64(fb[12:], off)
		fb = append(fb, []byte(mod)...)
		fb = append(fb, []byte(sym)...)
		return fb
	}
	out = append(out, enc(0xffffffc008001000, "kernel", "binder_ioctl", 0x100)...)
	out = append(out, enc(0x7f0000a000, "/x.so", "main", 0x10)...)

	rl := make([]byte, 2)
	binary.BigEndian.PutUint16(rl, 0)
	out = append(out, rl...)
	return out
}

func main() {
	vectors := []vector{
		// === Guard 1:size < 60 一律 stackTrace=null ===
		{note: "G1 empty", expectStackNull: true, build: func() []byte { return []byte{} }},
		{note: "G1 size=5", expectStackNull: true, build: func() []byte { return make([]byte, 5) }},
		{note: "G1 size=30", expectStackNull: true, build: func() []byte { return make([]byte, 30) }},
		{note: "G1 size=59", expectStackNull: true, build: func() []byte {
			b := make([]byte, 59)
			b[0] = 2
			return b
		}},

		// === size=60 ext_flags=0:正常事件,无 TLV → stackTrace=null ===
		{note: "size=60 extFlags=0 no TLV", expectStackNull: true, build: func() []byte {
			return baseHeader(0, 0)
		}},

		// === size=60 ext_flags=1 但无 TLV 字节(G4 fail-open)→ stackTrace=null ===
		{note: "size=60 extFlags=1 G4 (no TLV bytes)", expectStackNull: true, build: func() []byte {
			return baseHeader(1, 0)
		}},

		// === Guard 2:base+data_size 截断 → stackTrace=null(且整事件丢) ===
		{note: "G2 dataSize=100 but only 70B", expectStackNull: true, build: func() []byte {
			b := baseHeader(1, 100)
			return append(b, make([]byte, 10)...) // 70B 总,缺 90B parcel
		}},

		// === Guard 4:ext_flags=1 但 TLV 头不完整(< 5B)→ stackTrace=null ===
		{note: "G4 TLV header truncated (3B)", expectStackNull: true, build: func() []byte {
			b := baseHeader(1, 0)
			return append(b, []byte{0x01, 0x00, 0x00}...) // 只 3B,缺 2B
		}},

		// === Guard 5:tlv_length 超过剩余字节 → stackTrace=null ===
		{note: "G5 tlv_length=999 but only 0B payload", expectStackNull: true, build: func() []byte {
			b := baseHeader(1, 0)
			tlv := make([]byte, 5)
			tlv[0] = 0x01
			binary.BigEndian.PutUint32(tlv[1:], 999)
			return append(b, tlv...)
		}},

		// === 未知 TLV 类型 → 跳过(向前兼容)→ stackTrace=null ===
		{note: "Unknown TLV type 0x99", expectStackNull: true, build: func() []byte {
			b := baseHeader(1, 0)
			tlv := make([]byte, 5)
			tlv[0] = 0x99
			binary.BigEndian.PutUint32(tlv[1:], 4)
			return append(append(b, tlv...), make([]byte, 4)...)
		}},

		// === 完整合法 TLV → stackTrace 非 null(两端都应解出来) ===
		{note: "Valid STACK_TRACE TLV", expectStackNull: false, build: func() []byte {
			payload := minimalStackTLVPayload()
			b := baseHeader(1, 0)
			tlv := make([]byte, 5)
			tlv[0] = 0x01
			binary.BigEndian.PutUint32(tlv[1:], uint32(len(payload)))
			return append(append(b, tlv...), payload...)
		}},

		// === 带 parcel + 合法 TLV ===
		{note: "Parcel + STACK_TRACE TLV", expectStackNull: false, build: func() []byte {
			parcel := []byte{0xAA, 0xBB, 0xCC, 0xDD}
			payload := minimalStackTLVPayload()
			b := baseHeader(1, uint32(len(parcel)))
			b = append(b, parcel...)
			tlv := make([]byte, 5)
			tlv[0] = 0x01
			binary.BigEndian.PutUint32(tlv[1:], uint32(len(payload)))
			return append(append(b, tlv...), payload...)
		}},

		// === ext_flags=0 但后面追加垃圾字节 → stackTrace=null(Guard 3 早返) ===
		{note: "extFlags=0 with garbage tail", expectStackNull: true, build: func() []byte {
			b := baseHeader(0, 0)
			return append(b, []byte{0x01, 0x00, 0x00, 0xff, 0xff, 0xde, 0xad}...)
		}},

		// === fuzz size=200 全 0 ===
		{note: "size=200 all zeros", expectStackNull: true, build: func() []byte {
			return make([]byte, 200)
		}},

		// === fuzz size=200 v2 + extFlags=1 但 dataSize=0 + 5B TLV 头但 length 越界 ===
		{note: "size=200 G5 boundary", expectStackNull: true, build: func() []byte {
			b := baseHeader(1, 0)
			tlv := make([]byte, 5)
			tlv[0] = 0x01
			binary.BigEndian.PutUint32(tlv[1:], 1<<31) // 极大 length
			return append(append(b, tlv...), make([]byte, 200-65)...)
		}},
	}

	// 写文件
	out := []byte{}
	out = append(out, []byte("TLVF")...)
	cnt := make([]byte, 2)
	binary.BigEndian.PutUint16(cnt, uint16(len(vectors)))
	out = append(out, cnt...)
	for _, v := range vectors {
		payload := v.build()
		sn := make([]byte, 2)
		binary.BigEndian.PutUint16(sn, uint16(len(payload)))
		out = append(out, sn...)
		if v.expectStackNull {
			out = append(out, 1)
		} else {
			out = append(out, 0)
		}
		nb := []byte(v.note)
		nl := make([]byte, 2)
		binary.BigEndian.PutUint16(nl, uint16(len(nb)))
		out = append(out, nl...)
		out = append(out, nb...)
		out = append(out, payload...)
	}

	if err := os.WriteFile("tlv-fuzz-vectors.bin", out, 0644); err != nil {
		panic(err)
	}
	fmt.Printf("wrote tlv-fuzz-vectors.bin: %d vectors, %d bytes\n", len(vectors), len(out))
}
