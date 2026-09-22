package main

import "time"

const (
	// Only the immediate race-closing status read has a bound. Completion itself is delivered by the
	// IPN bus watcher; statusWaitWindow is a safety ceiling for a broken control plane.
	statusPollTimeout = 400 * time.Millisecond
	statusWaitWindow  = 2 * time.Second
)
