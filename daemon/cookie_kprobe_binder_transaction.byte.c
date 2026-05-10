//go:build ignore

// SPDX: Dual MIT/GPL — 与 binder_transaction.byte.c 同 license。
//
// 这个文件**不单独编译**,而是被 binder_transaction.byte.c 的尾部 #include 进同一个
// 编译单元,这样 trace_event_map / pending_map / tid_to_txid_map /
// ringbuf_overflow_map 等 map 都共用同一份定义,bpf2go 一次编译就能把多个 SEC 程序
// 写到同一个 .o,kernel 加载时也共享 map fd。
//
// 设计背景(spec daemon-bpf-load-known-issue):
//   旧实现把 cookie 分配 + pending_map 写入 + chunk loop 内 inc_overflow 全塞到主
//   kprobe 里,16 次完全展开 chunk loop 让 verifier 状态空间累积超过 1M instruction
//   路径限制(processed 4302 insns 的 stats 中间 truncated 47873 行)。
//
//   通用方案:把 cookie/sideband 三件套拆到独立 BPF program。同一 kprobe 多 program
//   attach 是 kernel 4.20+ 标准(perf event chain),cilium/ebpf 多次
//   `link.Kprobe("binder_transaction", ...)` 即可。两个 program 各自独立通过
//   verifier,主 chunk loop 等价 P4-B-1 baseline(已知能加载)。
//
// 本文件落地:
//   - SEC kprobe/binder_transaction 第二个 program(C 函数名 kprobe_binder_transaction_cookie)
//   - 不读 binder_transaction_data 的字段(不调 BPF_CORE_READ),verifier 复杂度极小
//   - 用 bpf_ktime_get_ns() 拿 cookie + 写 pending_map[current_tid]=cookie
//     (供同一 syscall context 内紧随其后触发的 raw_tp_binder_transaction 反查;
//     ns 精度 cookie,跨 CPU 同 ns 碰撞由 (tid<<8 | smp_processor_id) 复合键消解,
//     见 B4 修复)
//   - 读 tid_to_txid_map[current_tid] 拿主 program 写入的 transaction_id,
//     与 cookie 一起作为 binder_cookie_sideband record 上送
//   - 上送 binder_cookie_sideband record:
//       { kind = EVT_KIND_COOKIE, cookie, transaction_id, tid }
//     daemon 端按 transaction_id 把 cookie 注入对应主事件的 meta(主事件的 cookie
//     字段 BPF 端写 0,由本 sideband 填充),然后才进入 § 5.6.3 状态机。

SEC("kprobe/binder_transaction_cookie_path")
int kprobe_binder_transaction_cookie(struct pt_regs *ctx) {
    u32 config_key = 0;
    struct trace_config *conf = bpf_map_lookup_elem(&trace_config_map, &config_key);
    if (conf == NULL) {
        return 0;
    }

    // reply 路径不分配 cookie:reply 主事件直接走 kernel_debug_id,不需要 sideband
    // (spec § 5.6.2 "reply 路径走 kprobe 直读,不走 sideband")。
    int reply = PT_REGS_PARM4(ctx);
    if (reply) {
        return 0;
    }

    // 与主 program **完全一致**的 sender uid 过滤,否则会出现 cookie 已分配但主事件
    // 被过滤丢弃的情况,raw_tp 反查到后上送孤儿 sideband 让 daemon 落 sideband_orphan,
    // 但又没对应 ringbuf_overflow_main +1,验收等式破。
    u32 current_uid = bpf_get_current_uid_gid() >> 32;
    if ((conf->uid != 0) && (conf->uid != current_uid)) {
        return 0;
    }

    u32 current_tid = (u32)(bpf_get_current_pid_tgid() & 0xFFFFFFFF);

    // 读主 program 写入的 transaction_id。B5 attach 顺序修复后,主 program **一定**先
    // 跑过(LIFO),tid_to_txid_map[tid] 必定有效。lookup miss 路径:
    //   a) 主 program 自己 early return(tr==NULL / oversize)→ 主 program 没写
    //      tid_to_txid_map → 这里 miss
    //   b) tid_to_txid_map LRU 满 + 同 tid 被 evict → 极罕见
    //
    // 两种路径都视为"主事件不会到达 daemon",cookie program 直接 fail-fast 跳过
    // (避免上送孤儿 cookie sideband 让 daemon 落 pendingCookieByTxid → 5s TTL
    // evict 浪费状态空间)。daemon 端**不**实施 fuzzy 匹配。
    u64 *txid_p = bpf_map_lookup_elem(&tid_to_txid_map, &current_tid);
    if (!txid_p) {
        return 0;
    }
    u64 transaction_id = *txid_p;
    if (transaction_id == 0) {
        return 0;
    }

    // cookie 退化方案(B1 真机 A/B 实验定论 + B4 修复跨 CPU 碰撞):
    //   cookie = (ktime_ns << 8) | smp_processor_id
    //
    // 原 alloc_cookie() 用 __sync_fetch_and_add 全局 BPF_MAP_TYPE_ARRAY,A/B step
    // A1 真机 strace 显示该路径触发 EAGAIN→ENOSPC(verifier 对 BPF_ATOMIC|BPF_FETCH
    // 在共享 ARRAY map 上的复杂度展开拒绝;errno 实际是 E2BIG/ENOSPC,**不是**
    // ENOTSUPP 524 — 见 commit message)。
    //
    // B4 修复:仅 ktime_ns 跨 CPU 在同一 ns 内可能碰撞(arm64 ktime_get_ns 是 1ns
    // 精度,多核同 ns 触发 binder_transaction 不是"罕见")→ daemon 端
    // checkCookieDuplicate 会误报 cookieMonotonicViolation。把 ktime 左移 8 位,
    // 低 8 位塞 CPU id(Android 设备 ≤ 256 核,8 位足够),保证跨 CPU 不碰撞:
    //   - 同 CPU 同 ns 内:ktime 一致 + cpu 一致 → cookie 重复(理论可能,但同
    //     CPU 在 ns 级触发同一 syscall 概率极低,验证器靠 daemon counter 监测)
    //   - 跨 CPU 同 ns 内:ktime 一致 + cpu 不同 → cookie 必不重复
    //
    // 高 56 位 ktime_ns 可表示 ~2.3 年(2^56 ns),足够长(daemon 进程生命周期 ≪)。
    u64 ts = bpf_ktime_get_ns();
    u32 cpu = bpf_get_smp_processor_id();
    u64 cookie = (ts << 8) | (cpu & 0xff);
    if (cookie == 0) {
        return 0;
    }

    // pending_map 写入,raw_tp 反查同一 tid 拿 cookie 关联 sideband。
    bpf_map_update_elem(&pending_map, &current_tid, &cookie, BPF_ANY);

    // 上送 cookie sideband:
    // - transaction_id_hint:虽字段名带 "hint",B5 修复后语义实为**精确**主
    //   program transaction_id(由 tid_to_txid_map 桥接,B5 attach 顺序保证主先跑)。
    //   daemon 端按此键精确关联,无 fuzzy 后备。
    // - cookie:(ktime_ns << 8) | cpu_id,跨 CPU 不碰撞(B4 修复)。
    // - tid:仅作辅助 debug 信息;daemon 端不用 tid 做关联(transaction_id 是主键)。
    struct binder_cookie_sideband *e = bpf_ringbuf_reserve(
        &trace_event_map, sizeof(struct binder_cookie_sideband), 0);
    if (!e) {
        // cookie sideband ringbuf reserve 失败,沿用 sideband overflow 槽位计数。
        // pending_map 已写入但 daemon 没收到 cookie,主 event 走降级路径(cookie=0 emit),
        // raw_tp 后续上送的 sideband 因找不到 pendingMain 而 sideband_orphan。
        // 这与 spec § 6.2 不变量 sideband_orphan ≤ ringbuf_overflow_main 不直接对应,
        // 把 cookie reserve 失败也按 ringbuf_overflow_sideband 槽位累加(已知噪声)。
        inc_overflow(RB_OVF_SLOT_SIDEBAND);
        return 0;
    }

    e->kind = EVT_KIND_COOKIE;
    e->reserved0 = 0;
    e->reserved1 = 0;
    e->reserved2 = 0;
    e->reserved3 = 0;
    e->cookie = cookie;
    e->transaction_id_hint = transaction_id;  // B5 修复后必定有效
    e->tid = current_tid;
    e->reserved4 = 0;

    bpf_ringbuf_submit(e, 0);
    return 0;
}
