//go:build !windows && !linux

package main

import (
	"fmt"
	"os"
	"os/exec"
)

func execPath() (string, error) { return os.Executable() }

// On Linux (tests and local runs) there's no elevation and no window; just carry on.
func ensureAdmin() bool { return true }

func startupLaunch() bool { return false }

func openWindow(url string) {
	// Best effort, for local testing.
	exec.Command("xdg-open", url).Start()
}

func fatal(msg string) { fmt.Fprintln(os.Stderr, "SonderShield:", msg) }

func osMain() bool { return false }

func shareInstance(path string) {}
