package main

func pendingLoginState(loginURL string) result {
	if loginURL == "" {
		return result{State: "error", Error: "Tailscale authorization is still pending; reopen sign-in"}
	}
	return result{State: "needs_login", LoginURL: loginURL}
}
