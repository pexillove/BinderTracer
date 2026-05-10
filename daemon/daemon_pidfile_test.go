package main

import (
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// spec § 13.1.1:PID 文件生命周期测试 —— acquireSingleton 写入,releasePidFile 清理。
func TestAcquireSingleton_writesOwnPid(t *testing.T) {
	tmp := t.TempDir()
	pidFile := filepath.Join(tmp, "test.pid")

	d := &Daemon{
		logger:  log.New(os.Stderr, "", 0),
		pidFile: pidFile,
	}
	if err := d.acquireSingleton(); err != nil {
		t.Fatalf("acquireSingleton failed: %v", err)
	}

	content, err := os.ReadFile(pidFile)
	if err != nil {
		t.Fatalf("read PID file: %v", err)
	}
	got, err := strconv.Atoi(strings.TrimSpace(string(content)))
	if err != nil {
		t.Fatalf("PID file content not integer: %q", content)
	}
	if got != os.Getpid() {
		t.Errorf("PID file has %d, want %d", got, os.Getpid())
	}
	// 必须带尾 \n,否则 libsu cat 解析会拿不到(参见 BTraceManager 实战踩坑)
	if !strings.HasSuffix(string(content), "\n") {
		t.Errorf("PID file must end with newline; got %q", content)
	}
}

// spec § 13.1.2:单实例拒绝测试 —— PID 文件指向活进程时 acquireSingleton 必须拒绝。
func TestAcquireSingleton_rejectsWhenOwnerAlive(t *testing.T) {
	tmp := t.TempDir()
	pidFile := filepath.Join(tmp, "test.pid")
	// 写入当前进程自己的 PID:绝对活
	if err := os.WriteFile(pidFile, []byte(strconv.Itoa(os.Getpid())+"\n"), 0644); err != nil {
		t.Fatalf("pre-seed PID file: %v", err)
	}

	d := &Daemon{
		logger:  log.New(os.Stderr, "", 0),
		pidFile: pidFile,
	}
	err := d.acquireSingleton()
	if err == nil {
		t.Fatalf("acquireSingleton should reject when owner is alive, got nil")
	}
	if !strings.Contains(err.Error(), "已有旧实例存活") {
		t.Errorf("unexpected error message: %v", err)
	}
}

// spec § 13.1.1:PID 文件指向死 PID 时,acquireSingleton 覆盖并继续。
func TestAcquireSingleton_overridesDeadPid(t *testing.T) {
	tmp := t.TempDir()
	pidFile := filepath.Join(tmp, "test.pid")
	// 写一个绝不可能活的高 PID(32-bit max)
	if err := os.WriteFile(pidFile, []byte("2147483646\n"), 0644); err != nil {
		t.Fatalf("pre-seed: %v", err)
	}

	d := &Daemon{
		logger:  log.New(os.Stderr, "", 0),
		pidFile: pidFile,
	}
	if err := d.acquireSingleton(); err != nil {
		t.Fatalf("acquireSingleton should succeed over dead PID, got %v", err)
	}

	content, _ := os.ReadFile(pidFile)
	got, _ := strconv.Atoi(strings.TrimSpace(string(content)))
	if got != os.Getpid() {
		t.Errorf("after override, PID file has %d, want %d", got, os.Getpid())
	}
}

// spec § 8.2:releasePidFile 只删属于自己的 PID 文件。
func TestReleasePidFile_deletesSelfOwned(t *testing.T) {
	tmp := t.TempDir()
	pidFile := filepath.Join(tmp, "test.pid")
	_ = os.WriteFile(pidFile, []byte(strconv.Itoa(os.Getpid())+"\n"), 0644)

	d := &Daemon{
		logger:  log.New(os.Stderr, "", 0),
		pidFile: pidFile,
	}
	d.releasePidFile()

	if _, err := os.Stat(pidFile); !os.IsNotExist(err) {
		t.Errorf("PID file should be deleted; stat err=%v", err)
	}
}

// spec § 8.2:releasePidFile 绝不删别人家的 PID 文件(防止启动失败后误清邻居的锁)。
func TestReleasePidFile_skipsForeign(t *testing.T) {
	tmp := t.TempDir()
	pidFile := filepath.Join(tmp, "test.pid")
	_ = os.WriteFile(pidFile, []byte("123456\n"), 0644) // 不是 self

	d := &Daemon{
		logger:  log.New(os.Stderr, "", 0),
		pidFile: pidFile,
	}
	d.releasePidFile()

	if _, err := os.Stat(pidFile); os.IsNotExist(err) {
		t.Errorf("foreign PID file must NOT be deleted")
	}
}

// 空 pidFile 配置时,acquire/release 都是 no-op,不应 panic。
func TestPidFile_emptyPathIsNoOp(t *testing.T) {
	d := &Daemon{
		logger:  log.New(os.Stderr, "", 0),
		pidFile: "",
	}
	if err := d.acquireSingleton(); err != nil {
		t.Errorf("empty pidFile should be no-op, got %v", err)
	}
	d.releasePidFile() // 不应 panic
}
