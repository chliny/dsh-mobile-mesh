//go:build android

package main

import "tailscale.com/net/netns"

func registerAndroidSocketBinder() {
	netns.SetAndroidBindToNetworkFunc(bindAndroidSocket)
}
