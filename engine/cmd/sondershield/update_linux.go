//go:build linux

package main

import (
	"os"
	"os/exec"
	"time"
)

// selfReplace on Linux: the service swaps its own program file (/usr/local/bin) and asks
// systemd to restart it. The window comes from the same program, so it updates too; the
// AppImage itself only launches it and doesn't need replacing.
func selfReplace(exe, newExe string, onBeforeExit func()) error {
	if err := os.Chmod(newExe, 0o755); err != nil {
		os.Remove(newExe)
		return err
	}
	// Make sure the download really is a program for this computer before swapping.
	if out, err := exec.Command(newExe, "--version").Output(); err != nil || len(out) == 0 {
		os.Remove(newExe)
		return errBadUpdate
	}
	if err := os.Rename(newExe, exe); err != nil {
		os.Remove(newExe)
		return err
	}
	relabel(exe)
	if onBeforeExit != nil {
		onBeforeExit() // stop protection cleanly (it resumes after the restart)
	}
	go func() {
		time.Sleep(400 * time.Millisecond)
		exec.Command("systemctl", "restart", "--no-block", unitName).Run()
	}()
	return nil
}

var errBadUpdate = errorString("the downloaded update isn't a working SonderShield program")

type errorString string

func (e errorString) Error() string { return string(e) }
