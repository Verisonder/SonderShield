//go:build !windows

package main

import "errors"

// Self-update is Windows-only; on other platforms the app is managed by its package.
func selfReplace(exe, newExe string, onBeforeExit func()) error {
	return errors.New("self-update is only available on Windows")
}
