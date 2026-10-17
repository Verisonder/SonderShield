//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

func execPath() (string, error) { return os.Executable() }

// ensureAdmin returns true when we already have administrator rights. Otherwise it
// relaunches the program elevated (the UAC prompt) and returns false so this copy exits.
func ensureAdmin() bool {
	if windows.GetCurrentProcessToken().IsElevated() {
		return true
	}
	exe, err := os.Executable()
	if err != nil {
		return true // can't relaunch; carry on and let it fail with a clear message
	}
	verb, _ := syscall.UTF16PtrFromString("runas")
	file, _ := syscall.UTF16PtrFromString(exe)
	args, _ := syscall.UTF16PtrFromString(strings.Join(os.Args[1:], " "))
	_ = windows.ShellExecute(0, verb, file, args, nil, windows.SW_SHOWNORMAL)
	return false
}

// openWindow shows the UI. Edge or Chrome in "app" mode looks like a native window; if
// neither is found, the default browser is used.
func openWindow(url string) {
	if browser := appModeBrowser(); browser != "" {
		c := exec.Command(browser, "--app="+url, "--window-size=1100,760")
		c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		if c.Start() == nil {
			return
		}
	}
	file, _ := syscall.UTF16PtrFromString(url)
	open, _ := syscall.UTF16PtrFromString("open")
	windows.ShellExecute(0, open, file, nil, nil, windows.SW_SHOWNORMAL)
}

func appModeBrowser() string {
	pf := os.Getenv("ProgramFiles")
	pfx := os.Getenv("ProgramFiles(x86)")
	local := os.Getenv("LocalAppData")
	for _, p := range []string{
		filepath.Join(pf, "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(pfx, "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(pf, "Google", "Chrome", "Application", "chrome.exe"),
		filepath.Join(pfx, "Google", "Chrome", "Application", "chrome.exe"),
		filepath.Join(local, "Google", "Chrome", "Application", "chrome.exe"),
	} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

var (
	moduser32      = windows.NewLazySystemDLL("user32.dll")
	procMessageBox = moduser32.NewProc("MessageBoxW")
)

func fatal(msg string) {
	t, _ := syscall.UTF16PtrFromString("SonderShield")
	m, _ := syscall.UTF16PtrFromString(msg)
	procMessageBox.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), 0x10) // MB_ICONERROR
}
