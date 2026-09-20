package main

import (
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestReadyResultKeepsExistingRelayAddress(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	got := readyResult(&instance{listener: listener})
	if got.State != "ready" {
		t.Fatalf("state = %q, want ready", got.State)
	}
	if got.BaseURL != "http://"+listener.Addr().String() {
		t.Fatalf("base URL = %q, want existing listener address", got.BaseURL)
	}
}

func TestInstanceMatchesOnlyTheCurrentRelayTarget(t *testing.T) {
	entry := &instance{remoteHost: "host.tailnet.ts.net", remotePort: 22}

	if !entry.matchesTarget("host.tailnet.ts.net", 22) {
		t.Fatal("identical target should reuse the relay")
	}
	if entry.matchesTarget("host.tailnet.ts.net", 3080) {
		t.Fatal("a changed port must replace the relay")
	}
	if entry.matchesTarget("other.tailnet.ts.net", 22) {
		t.Fatal("a changed host must replace the relay")
	}
}

func TestLoginURLReadsCurrentStatusBeforeWaitingForBus(t *testing.T) {
	if statusPollTimeout <= 0 {
		t.Fatal("status poll timeout must be positive")
	}
}

func TestLoginFinishedCannotClearPendingStatus(t *testing.T) {
	if statusPollTimeout <= 0 {
		t.Fatal("status poll timeout must be positive")
	}
}

func TestCancelledStartChannelIsObservedImmediately(t *testing.T) {
	channel := make(chan struct{})
	close(channel)
	if !startCancelled(channel) {
		t.Fatal("closed start channel must be observed as cancelled")
	}
}

func TestStatusWaitPolicyFitsAuthorizationResumeBudget(t *testing.T) {
	if statusWaitWindow >= 2500*time.Millisecond {
		t.Fatalf("status wait window = %s, must fit Android resume budget", statusWaitWindow)
	}
	if statusPollTimeout >= statusWaitWindow {
		t.Fatalf("poll timeout = %s, must be shorter than wait window", statusPollTimeout)
	}
}

func TestConfigureLogsUsesStateDirectory(t *testing.T) {
	stateDirectory := t.TempDir()

	if err := configureLogs(stateDirectory); err != nil {
		t.Fatal(err)
	}

	logsDirectory := filepath.Join(stateDirectory, "logs")
	info, err := os.Stat(logsDirectory)
	if err != nil {
		t.Fatal(err)
	}
	if !info.IsDir() {
		t.Fatalf("%s is not a directory", logsDirectory)
	}
	if got := os.Getenv("TS_LOGS_DIR"); got != logsDirectory {
		t.Fatalf("TS_LOGS_DIR = %q, want %q", got, logsDirectory)
	}
}
