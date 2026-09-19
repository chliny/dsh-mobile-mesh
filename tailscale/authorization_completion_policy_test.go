package main

import "testing"

func TestLoginCompletedRequiresAuthoritativeRunningState(t *testing.T) {
	if !loginCompleted("", true) {
		t.Fatal("empty URL plus running status should be treated as completed login")
	}
	if loginCompleted("https://login.tailscale.com/a/example", true) {
		t.Fatal("a login URL still means authorization is pending")
	}
	if loginCompleted("", false) {
		t.Fatal("empty URL without running status must not be treated as completed")
	}
}
