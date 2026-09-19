package main

import "testing"

func TestPendingLoginStateStillRequiresURLForActualPendingLogin(t *testing.T) {
	got := pendingLoginState("https://login.tailscale.com/a/example")
	if got.State != "needs_login" || got.LoginURL == "" {
		t.Fatalf("pending state = %+v, want needs_login with URL", got)
	}
}

func TestPendingLoginStateDoesNotClaimCompletedAuthorization(t *testing.T) {
	got := pendingLoginState("")
	if got.State != "error" {
		t.Fatalf("empty URL state = %+v, want explicit error for unresolved state", got)
	}
}
