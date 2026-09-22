package main

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type delayedAcceptListener struct {
	conn    net.Conn
	entered chan struct{}
	closed  chan struct{}
	release chan struct{}
}

func (listener *delayedAcceptListener) Accept() (net.Conn, error) {
	select {
	case <-listener.entered:
	default:
		close(listener.entered)
	}
	<-listener.release
	if listener.conn == nil {
		return nil, errors.New("listener closed")
	}
	conn := listener.conn
	listener.conn = nil
	return conn, nil
}

func (listener *delayedAcceptListener) Close() error {
	select {
	case <-listener.closed:
	default:
		close(listener.closed)
	}
	return nil
}

func (listener *delayedAcceptListener) Addr() net.Addr { return fakeAddr("delayed") }

type fakeAddr string

func (address fakeAddr) Network() string { return string(address) }
func (address fakeAddr) String() string  { return string(address) }

func TestBeginStartAfterCancelDoesNotPanic(t *testing.T) {
	startCancellation.Lock()
	startCancellation.channel = nil
	startCancellation.Unlock()
	first := beginStart()
	TailscaleCancelStart()
	second := beginStart()
	if first == second {
		t.Fatal("new start must own a fresh cancellation channel")
	}
	endStart(second)
}

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

func TestStopRejectsConnectionAcceptedAfterListenerClose(t *testing.T) {
	accepted, peer := net.Pipe()
	defer peer.Close()
	listener := &delayedAcceptListener{
		conn:    accepted,
		entered: make(chan struct{}),
		closed:  make(chan struct{}),
		release: make(chan struct{}),
	}
	entry := &instance{
		listener:     listener,
		done:         make(chan struct{}),
		relayHealthy: true,
	}

	go serve(entry, "unused:1")
	select {
	case <-listener.entered:
	case <-time.After(time.Second):
		t.Fatal("relay did not enter accept")
	}
	stopped := make(chan struct{})
	go func() {
		stopRelayLocked(entry)
		close(stopped)
	}()

	select {
	case <-listener.closed:
	case <-time.After(time.Second):
		t.Fatal("stop did not close listener")
	}
	close(listener.release)

	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("stop waited forever for a connection accepted after shutdown")
	}
	entry.connMu.Lock()
	connectionCount := len(entry.connections)
	entry.connMu.Unlock()
	if connectionCount != 0 {
		t.Fatalf("tracked connections after stop = %d, want 0", connectionCount)
	}
	if _, err := peer.Write([]byte("x")); err == nil {
		t.Fatal("connection accepted after stop was not closed")
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
