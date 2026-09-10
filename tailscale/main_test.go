package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestConfigureLogsUsesStateDirectory(t *testing.T) {
	stateDirectory := t.TempDir()

	if err := configureLogs(stateDirectory); err != nil {
		t.Fatal(err)
	}

	logsDirectory := filepath.Join(stateDirectory, "logs")
	info, err := os.Stat(logsDirectory)
	if err != nil {
		t.Fatal(err)
	}
	if !info.IsDir() {
		t.Fatalf("%s is not a directory", logsDirectory)
	}
	if got := os.Getenv("TS_LOGS_DIR"); got != logsDirectory {
		t.Fatalf("TS_LOGS_DIR = %q, want %q", got, logsDirectory)
	}
}
