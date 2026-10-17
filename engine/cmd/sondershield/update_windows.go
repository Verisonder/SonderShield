//go:build windows

package main

import (
	"os"
	"os/exec"
	"syscall"
	"time"
)

// selfReplace swaps the running exe for newExe and relaunches. Windows won't let a running
// program be overwritten or deleted, but it can be renamed, so: move the old one aside,
// move the new one into its place, start it, and exit. The fresh copy clears the .old file.
func selfReplace(exe, newExe string, onBeforeExit func()) error {
	old := exe + ".old"
	os.Remove(old)
	if err := os.Rename(exe, old); err != nil {
		os.Remove(newExe)
		return err
	}
	if err := os.Rename(newExe, exe); err != nil {
		os.Rename(old, exe) // put it back
		return err
	}
	// Launch the new version (keep the window state: --tray means stay in the background).
	args := []string{}
	if hasFlag("--tray") {
		args = append(args, "--tray")
	}
	cmd := exec.Command(exe, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		return err
	}
	if onBeforeExit != nil {
		onBeforeExit() // stop protection cleanly, restore DNS
	}
	go func() { time.Sleep(400 * time.Millisecond); os.Exit(0) }()
	return nil
}
