package main

// loginCompleted identifies loginURL's empty-success result: the login watcher
// observed completion and the authoritative status poll confirmed Running.
func loginCompleted(loginURL string, running bool) bool {
	return loginURL == "" && running
}
