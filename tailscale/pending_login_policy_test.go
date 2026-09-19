package main

import "testing"

func TestPendingLoginStateRequiresFreshLoginWhenURLMissing(t *testing.T) {
	got := pendingLoginState("")
	if got.State != "error" {
		t.Fatalf("state = %q, want error", got.State)
	}
}

func TestPendingLoginStateReturnsWebViewStateWhenURLWasRefreshed(t *testing.T) {
	got := pendingLoginState("https://login.tailscale.com/a/refreshed")
	if got.State != "needs_login" || got.LoginURL != "https://login.tailscale.com/a/refreshed" {
		t.Fatalf("got %+v, want refreshed needs_login result", got)
	}
}

func TestPendingLoginStateKeepsLoginURL(t *testing.T) {
	got := pendingLoginState("https://login.tailscale.com/a/example")
	if got.State != "needs_login" || got.LoginURL == "" {
		t.Fatalf("got %+v, want needs_login with URL", got)
	}
}
