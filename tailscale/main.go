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
	server     *tsnet.Server
	listener   net.Listener
	done       chan struct{}
	stateDir   string
	hostname   string
	remoteHost string
	remotePort int
	loginURL   string
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
	if entry := current.value; entry != nil && entry.listener == nil && entry.stateDir == stateDirectory && entry.hostname == deviceHostname {
		client, clientErr := entry.server.LocalClient()
		if clientErr == nil {
			status, err := getStatus(client)
			if err == nil && status.BackendState == ipn.Running.String() {
				return encode(startRelayLocked(entry.server, entry.remoteHost, entry.remotePort))
			}
		}
		return encode(result{State: "needs_login", LoginURL: entry.loginURL})
	}
	stopLocked()
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
		_ = server.Close()
		return encode(result{State: "error", Error: err.Error()})
	}
	if status.BackendState != ipn.Running.String() {
		login, loginErr := loginURL(client)
		if loginErr != nil {
			_ = server.Close()
			return encode(result{State: "error", Error: loginErr.Error()})
		}
		current.value = &instance{
			server: server, stateDir: stateDirectory, hostname: deviceHostname,
			remoteHost: targetHost, remotePort: targetPort, loginURL: login,
		}
		return encode(result{State: "needs_login", LoginURL: login})
	}
	return encode(startRelayLocked(server, targetHost, targetPort))
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
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
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
		if notify.State != nil && *notify.State == ipn.Running {
			return "", nil
		}
	}
}

func startRelayLocked(server *tsnet.Server, remoteHost string, remotePort int) result {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		_ = server.Close()
		return result{State: "error", Error: err.Error()}
	}
	entry := &instance{server: server, listener: listener, done: make(chan struct{}), remoteHost: remoteHost, remotePort: remotePort}
	current.value = entry
	go serve(entry, net.JoinHostPort(remoteHost, strconv.Itoa(remotePort)))
	return result{State: "ready", BaseURL: "http://" + listener.Addr().String()}
}

func serve(entry *instance, remote string) {
	defer close(entry.done)
	for {
		local, err := entry.listener.Accept()
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
	if entry.listener != nil {
		_ = entry.listener.Close()
		<-entry.done
	}
	_ = entry.server.Close()
	current.value = nil
}

func encode(value result) *C.char {
	data, _ := json.Marshal(value)
	return C.CString(string(data))
}

func main() {}
