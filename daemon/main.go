package main

import (
	"flag"
	"log"
)

// main 只承担 daemon 模式:连回 App,转发 eBPF 事件。
// 解析(接口名、方法名、包名)由 App 端完成,这里不嵌入 methods.json 之类的映射。
func main() {
	var listenAddr string
	var targetUid uint
	var logFile string
	var pidFile string
	var sessionToken string

	flag.StringVar(&listenAddr, "l", DefaultListenAddr, "tcp server address of the viewer app")
	flag.UintVar(&targetUid, "u", 0, "target uid (0 means no filter)")
	flag.StringVar(&logFile, "f", "", "log file path (empty = stdout)")
	// spec § 8.2:PID 文件归属切回 daemon 自己维护;app 仅负责清理失效残留。
	flag.StringVar(&pidFile, "p", "/data/local/tmp/btrace.pid", "pid file path (empty disables singleton lock)")
	// spec § 8.4:app 启动 daemon 时生成的会话令牌,daemon 在 ACK 里回传以证明
	// "这次连接确实属于这次新启动"。空字符串表示无会话校验(向后兼容旧 app)。
	flag.StringVar(&sessionToken, "t", "", "session token (echoed back in ACK to prove this is the new instance)")
	flag.Parse()

	d, err := NewDaemon(DaemonConfig{
		ListenAddr:   listenAddr,
		TargetUid:    uint32(targetUid),
		LogFile:      logFile,
		PidFile:      pidFile,
		SessionToken: sessionToken,
	})
	if err != nil {
		log.Fatalf("创建守护进程失败: %v", err)
	}
	if err := d.Run(); err != nil {
		log.Fatalf("运行守护进程失败: %v", err)
	}
}
