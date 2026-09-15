package main

import "time"

const (
	statusPollTimeout = 400 * time.Millisecond
	statusWaitWindow  = 2 * time.Second
	statusPollDelay   = 100 * time.Millisecond
)
