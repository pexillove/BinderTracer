GO_RUN = go run github.com/cilium/ebpf/cmd/bpf2go
GO_MOD_TIDY = go mod tidy
# Cross-compile for Android (linux/arm64), no cgo so it builds anywhere
GO_BUILD = GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build
TARGET = arm64
PACKAGE = main
TYPE1 = binder_trace_event
TYPE2 = trace_config
# spec-a: P4-B-2+3 raw_tp sideband event(raw_tp_binder_transaction.byte.c
# 通过 #include 合并进同一编译单元,共享 trace_event_map / pending_map /
# ringbuf_overflow_map 等 map,见 spec § 5.6 融合方案 C)
TYPE3 = binder_sideband_event
# spec-b: 调用栈 record(全量,spec § 4.1.2 双 record)
TYPE4 = stack_trace_event
# spec daemon-bpf-load-known-issue:cookie 旁路 kprobe sideband
# (cookie_kprobe_binder_transaction.byte.c 通过 #include 合并进同一编译单元,
# 与主 kprobe + raw_tp 共享 cookie_gen_map / pending_map / trace_event_map)
TYPE5 = binder_cookie_sideband
BPF_FILE = binder_transaction.byte.c
HEADERS = ./headers
BPF_OUTPUT = bpf
BINARY_NAME = btrace

all: bpf build

bpf:
	# -mcpu=v3:沿用 BPF ISA 第 3 版,kernel 5.12+ / Android 13 / GKI 5.15 满足。
	# 历史的 stack_capture 滚动窗口采样路径已删除,但保留 v3 以与既有 verifier 通过状态对齐。
	$(GO_RUN) -cflags "-O2 -g -Wall -mcpu=v3" -go-package $(PACKAGE) --target=$(TARGET) -type $(TYPE1) -type $(TYPE2) -type $(TYPE3) -type $(TYPE4) -type $(TYPE5) $(BPF_OUTPUT) $(BPF_FILE) -- -I$(HEADERS)

tidy:
	$(GO_MOD_TIDY)

build:
	$(GO_BUILD) -o $(BINARY_NAME)

clean:
	rm -f $(BPF_OUTPUT)_$(TARGET)_bpfel.go $(BPF_OUTPUT)_$(TARGET)_bpfel.o $(BINARY_NAME)

.PHONY: all bpf tidy build clean
