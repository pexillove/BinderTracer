//go:build ignore

// SPDX: Dual MIT/GPL — 与 binder_transaction.byte.c 同 license。
//
// 这个文件**不单独编译**,而是被 binder_transaction.byte.c 的尾部 #include 进同一个
// 编译单元,这样 trace_event_map / pending_map / ringbuf_overflow_map 等 map 都共用
// 同一份定义,bpf2go 一次编译就能把两个 SEC 程序写到同一个 .o,kernel 加载时也共享 map fd。
//
// 设计说明见 spec § 5.6 融合方案 C / § 6.5 跨 GKI 兼容。
//
// 关键约束(请勿改动):
//   * reply 路径直接 return —— reply 端走 entry kprobe 直读 thread->transaction_stack
//     ->debug_id 当 kernel_debug_id 已经够,不需要 sideband(避免重复事件 + 简化 daemon 状态机)。
//   * to_uid 字段链跨 GKI 不一致(GKI 13+ binder_proc.cred 直接存在,12-5.10 在 binder_proc_ext);
//     强制用 bpf_core_field_exists(struct binder_proc, cred) 门控,字段不存在时 to_uid=0 +
//     to_uid_unsupported=1。**不要**回退到 t->to_proc->tsk->cred(__rcu 字段,verifier 拒绝)。
//   * 整段 type+field 形式调用 bpf_core_field_exists,不用 lvalue 形式(spec § 5.6.1 调用形式约定)。

// raw_tracepoint/binder_transaction 的 ctx 是 unsigned long long ctx[3]:
//   ctx[0] = int reply        (kernel TP_PROTO 第 1 参数,bool/int)
//   ctx[1] = struct binder_transaction *t
//   ctx[2] = struct binder_node *target_node
// 我们手工解 ctx 数组,不使用 BPF_PROG 宏,避免 int <-> ptr 隐式转换告警。

SEC("raw_tracepoint/binder_transaction")
int raw_tp_binder_transaction(struct bpf_raw_tracepoint_args *args) {
    // 配置过滤复用 entry kprobe 同一份 trace_config_map
    u32 config_key = 0;
    struct trace_config *conf = bpf_map_lookup_elem(&trace_config_map, &config_key);
    if (conf == NULL) {
        return 0;
    }

    int reply = (int)args->args[0];
    if (reply) {
        // reply 路径不走 sideband(spec § 5.6.2)。
        // 注意:不能依赖 reply 路径反查 pending_map 来"清栈",因为同一线程在请求/回复
        // 之间会有 schedule 让出,reply 时 current_tid 与请求时未必相同。
        return 0;
    }

    struct binder_transaction *t = (struct binder_transaction *)args->args[1];
    if (!t) {
        return 0;
    }

    u32 current_tid = (u32)(bpf_get_current_pid_tgid() & 0xFFFFFFFF);

    // 反查 entry kprobe 留下的 cookie。miss 视为 entry early return / 过滤掉 / pending_map LRU
    // evict 等情况,沉默跳过即可(daemon 主记录在 5s TTL 后会落 entry_early_return)。
    u64 *cookie_p = bpf_map_lookup_elem(&pending_map, &current_tid);
    if (!cookie_p) {
        return 0;
    }
    u64 cookie = *cookie_p;
    if (cookie == 0) {
        return 0;
    }

    // 立即清 pending_map[current_tid],避免后续 entry 串单(防御性,
    // spec § 5.6.2 "立即清,避免后续 entry 串单(防御性)")。
    bpf_map_delete_elem(&pending_map, &current_tid);

    // 一站式读 sideband 字段 ——
    // debug_id / to_pid 字段链 GKI 13+/12-5.10 都存在,直接 BPF_CORE_READ;
    // to_uid 必须用 bpf_core_field_exists 门控。
    u64 kernel_debug_id = (u64)BPF_CORE_READ(t, debug_id);
    u32 to_pid = (u32)BPF_CORE_READ(t, to_proc, tsk, pid);

    u32 to_uid = 0;
    u8 to_uid_unsupported = 0;
    if (bpf_core_field_exists(struct binder_proc, cred)) {
        // GKI 13+:binder_proc.cred 直接可达,且 binder.c 注释明确 binder_open 后不变,非 __rcu。
        to_uid = (u32)BPF_CORE_READ(t, to_proc, cred, euid.val);
    } else {
        // GKI 12-5.10:binder_proc 没有 cred 字段(在 binder_proc_ext),进降级分支。
        // 注意:这一支在 13+ 设备上加载时被 libbpf 重定位为常量 1 → 编译期/加载期死代码消除,
        // 不会留指令在指令流里;反向同理。所以一份 .o 跨 GKI 不会 verifier 拒绝。
        to_uid_unsupported = 1;
    }

    struct binder_sideband_event *e = bpf_ringbuf_reserve(&trace_event_map, sizeof(struct binder_sideband_event), 0);
    if (!e) {
        // sideband ringbuf reserve 失败,按 sideband 槽计数(spec § 5.6.3)
        inc_overflow(RB_OVF_SLOT_SIDEBAND);
        return 0;
    }

    e->kind = EVT_KIND_SIDEBAND;
    e->to_uid_unsupported = to_uid_unsupported;
    e->reserved0 = 0;
    e->reserved1 = 0;
    e->reserved2 = 0;
    e->cookie = cookie;
    e->kernel_debug_id = kernel_debug_id;
    e->to_pid = to_pid;
    e->to_uid = to_uid;

    bpf_ringbuf_submit(e, 0);
    return 0;
}
