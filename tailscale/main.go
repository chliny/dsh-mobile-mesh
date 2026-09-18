package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type result struct {
	State    string `json:"state"`
	BaseURL  string `json:"baseUrl,omitempty"`
	LoginURL string `json:"loginUrl,omitempty"`
	Error    string `json:"error,omitempty"`
}

type instance struct {
	server       *tsnet.Server
	listener     net.Listener
	done         chan struct{}
	stateDir     string
	hostname     string
	remoteHost   string
	remotePort   int
	loginURL     string
	relayMu      sync.RWMutex
	relayHealthy bool
}

var current struct {
	sync.Mutex
	value *instance
}

type androidInterface struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	Addresses []string `json:"addresses"`
}

var interfaces struct {
	sync.RWMutex
	values []netmon.Interface
}

//export TailscaleSetInterfaces
func TailscaleSetInterfaces(value *C.char) {
	var source []androidInterface
	if json.Unmarshal([]byte(C.GoString(value)), &source) != nil {
		return
	}
	values := make([]netmon.Interface, 0, len(source))
	for _, item := range source {
		if item.Name == "" || item.Index < 1 {
			continue
		}
		addresses := make([]net.Addr, 0, len(item.Addresses))
		for _, raw := range item.Addresses {
			ip, subnet, err := net.ParseCIDR(raw)
			if err == nil {
				subnet.IP = ip
				addresses = append(addresses, subnet)
			}
		}
		values = append(values, netmon.Interface{Interface: &net.Interface{Name: item.Name, Index: item.Index, Flags: net.FlagUp}, AltAddrs: addresses})
	}
	interfaces.Lock()
	interfaces.values = values
	interfaces.Unlock()
}

//export TailscaleStart
func TailscaleStart(stateDir, hostname, remoteHost *C.char, port C.int) *C.char {
	current.Lock()
	defer current.Unlock()
	stateDirectory := C.GoString(stateDir)
	deviceHostname := C.GoString(hostname)
	targetHost := C.GoString(remoteHost)
	targetPort := int(port)
	if entry := current.value; entry != nil && entry.stateDir == stateDirectory && entry.hostname == deviceHostname {
		if entry.listener != nil {
			if relayReady(entry) && entry.matchesTarget(targetHost, targetPort) {
				return encode(readyResult(entry))
			}
			stopRelayLocked(entry)
			return encode(startRelayLocked(entry.server, targetHost, targetPort))
		}
		entry.remoteHost = targetHost
		entry.remotePort = targetPort
		client, clientErr := entry.server.LocalClient()
		if clientErr == nil && waitForRunning(client) {
			return encode(startRelayLocked(entry.server, targetHost, targetPort))
		}
		return encode(pendingLoginState(entry.loginURL))
	}
	stopLocked()
	if err := configureLogs(stateDirectory); err != nil {
		return encode(result{State: "error", Error: err.Error()})
	}
	server := &tsnet.Server{Dir: stateDirectory, Hostname: deviceHostname}
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		interfaces.RLock()
		defer interfaces.RUnlock()
		return append([]netmon.Interface(nil), interfaces.values...), nil
	})
	registerAndroidSocketBinder()
	if err := server.Start(); err != nil {
		return encode(result{State: "error", Error: err.Error()})
	}
	client, err := server.LocalClient()
	if err != nil {
		_ = server.Close()
		return encode(result{State: "error", Error: err.Error()})
	}
	status, err := getStatus(client)
	if err != nil {
		// tsnet can return a transient status error while the login URL has already been
		// published on the IPN bus. Keep the server alive and let the authorization watcher
		// surface that URL instead of converting the attempt into a dead connection.
		login, loginErr := loginURL(client)
		if loginErr == nil && login != "" {
			current.value = &instance{server: server, stateDir: stateDirectory, hostname: deviceHostname,
				remoteHost: targetHost, remotePort: targetPort, loginURL: login}
			return encode(pendingLoginState(login))
		}
		_ = server.Close()
		return encode(result{State: "error", Error: firstError(err, loginErr).Error()})
	}
	if status.BackendState != ipn.Running.String() {
		// The local status may be warming up while the IPN bus has already emitted BrowseToURL.
		// Watch first so the login URL is returned as soon as it exists instead of blocking on a
		// second status call that can wait for control-plane readiness.
		login, loginErr := loginURL(client)
		if loginErr == nil {
			current.value = &instance{server: server, stateDir: stateDirectory, hostname: deviceHostname,
				remoteHost: targetHost, remotePort: targetPort, loginURL: login}
			return encode(pendingLoginState(login))
		}
		current.value = &instance{server: server, stateDir: stateDirectory, hostname: deviceHostname,
			remoteHost: targetHost, remotePort: targetPort}
		return encode(result{State: "error", Error: loginErr.Error()})
	}
	current.value = &instance{server: server, stateDir: stateDirectory, hostname: deviceHostname}
	return encode(startRelayLocked(server, targetHost, targetPort))
}

// Android does not provide the Unix cache/current-directory fallbacks used by tsnet's log policy.
// Keep all Tailscale runtime files inside the app-owned connection directory instead.
func configureLogs(stateDirectory string) error {
	logsDirectory := filepath.Join(stateDirectory, "logs")
	if err := os.MkdirAll(logsDirectory, 0700); err != nil {
		return err
	}
	return os.Setenv("TS_LOGS_DIR", logsDirectory)
}

//export TailscaleStop
func TailscaleStop() *C.char {
	current.Lock()
	stopLocked()
	current.Unlock()
	return encode(result{State: "stopped"})
}

//export TailscaleFree
func TailscaleFree(value *C.char) { C.free(unsafe.Pointer(value)) }

func getStatus(client *local.Client) (*ipnstate.Status, error) {
	return getStatusWithTimeout(client, 3*time.Second)
}

func waitForRunning(client *local.Client) bool {
	deadline := time.Now().Add(statusWaitWindow)
	for time.Now().Before(deadline) {
		status, err := getStatusWithTimeout(client, statusPollTimeout)
		if err == nil && status.BackendState == ipn.Running.String() {
			return true
		}
		time.Sleep(statusPollDelay)
	}
	return false
}

func firstError(primary, secondary error) error {
	if primary != nil {
		return primary
	}
	return secondary
}

func getStatusWithTimeout(client *local.Client, timeout time.Duration) (*ipnstate.Status, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return client.Status(ctx)
}

func loginURL(client *local.Client) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	watcher, err := client.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		return "", err
	}
	defer watcher.Close()
	for {
		notify, err := watcher.Next()
		if err != nil {
			return "", err
		}
		if notify.BrowseToURL != nil {
			return *notify.BrowseToURL, nil
		}
		if notify.InitialStatus != nil && notify.InitialStatus.AuthURL != "" {
			return notify.InitialStatus.AuthURL, nil
		}
		// Google/Tailscale may complete login while the Android WebView is in another
		// Activity. LoginFinished is the authoritative event; keep the URL caller-owned
		// so Android can perform one final status poll and close its dialog itself.
		if notify.LoginFinished != nil {
			return "", nil
		}
		if notify.State != nil && *notify.State == ipn.Running {
			return "", nil
		}
	}
}

func startRelayLocked(server *tsnet.Server, remoteHost string, remotePort int) result {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		if current.value != nil && current.value.server == server {
			current.value = nil
		}
		_ = server.Close()
		return result{State: "error", Error: err.Error()}
	}
	entry := current.value
	if entry == nil || entry.server != server {
		entry = &instance{server: server}
	}
	entry.relayMu.Lock()
	entry.listener = listener
	entry.done = make(chan struct{})
	entry.relayHealthy = true
	entry.relayMu.Unlock()
	entry.remoteHost = remoteHost
	entry.remotePort = remotePort
	current.value = entry
	go serve(entry, net.JoinHostPort(remoteHost, strconv.Itoa(remotePort)))
	return result{State: "ready", BaseURL: "http://" + listener.Addr().String()}
}

func (entry *instance) matchesTarget(remoteHost string, remotePort int) bool {
	return entry.remoteHost == remoteHost && entry.remotePort == remotePort
}

func readyResult(entry *instance) result {
	entry.relayMu.RLock()
	defer entry.relayMu.RUnlock()
	if entry.listener == nil {
		return result{State: "error", Error: "Tailscale relay is no longer available"}
	}
	return result{State: "ready", BaseURL: "http://" + entry.listener.Addr().String()}
}

func relayReady(entry *instance) bool {
	entry.relayMu.RLock()
	defer entry.relayMu.RUnlock()
	return entry.listener != nil && entry.relayHealthy
}

func serve(entry *instance, remote string) {
	defer func() {
		entry.relayMu.Lock()
		entry.relayHealthy = false
		done := entry.done
		entry.relayMu.Unlock()
		if done != nil {
			close(done)
		}
	}()
	for {
		entry.relayMu.RLock()
		listener := entry.listener
		entry.relayMu.RUnlock()
		if listener == nil {
			return
		}
		local, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			defer local.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			remoteConn, err := entry.server.Dial(ctx, "tcp", remote)
			cancel()
			if err != nil {
				return
			}
			defer remoteConn.Close()
			go io.Copy(remoteConn, local)
			io.Copy(local, remoteConn)
		}()
	}
}

func stopLocked() {
	entry := current.value
	if entry == nil {
		return
	}
	stopRelayLocked(entry)
	_ = entry.server.Close()
	current.value = nil
}

func stopRelayLocked(entry *instance) {
	entry.relayMu.Lock()
	listener := entry.listener
	done := entry.done
	entry.listener = nil
	entry.relayHealthy = false
	entry.relayMu.Unlock()
	if listener == nil {
		return
	}
	_ = listener.Close()
	if done != nil {
		<-done
		entry.relayMu.Lock()
		if entry.done == done {
			entry.done = nil
		}
		entry.relayMu.Unlock()
	}
}

func encode(value result) *C.char {
	data, _ := json.Marshal(value)
	return C.CString(string(data))
}

func main() {}
