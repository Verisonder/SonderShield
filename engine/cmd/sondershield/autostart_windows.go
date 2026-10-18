//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"unicode/utf16"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// Start with Windows.
//
// Two parts:
//   - a Run entry (HKCU\...\Run "SonderShield" = "<exe>" --startup), so it shows in
//     Task Manager › Startup apps and can be switched off there;
//   - a scheduled task "SonderShield" with no trigger, set to run with full rights.
//     The Run entry starts the exe without rights; it asks the task to start the real
//     copy, so there's no UAC prompt at sign-in.
//
// The task is written from XML so the defaults that stopped it before are off:
// "start only on AC power" (laptops on battery never started) and the 3-day run limit.

const (
	runKeyPath      = `Software\Microsoft\Windows\CurrentVersion\Run`
	approvedKeyPath = `Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run`
	autoName        = "SonderShield"
)

func (p *windowsPlatform) setAutostart(on bool, exe string) error {
	if !on {
		run("schtasks", "/delete", "/tn", autoName, "/f")
		if k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE); err == nil {
			k.DeleteValue(autoName)
			k.Close()
		}
		clearStartupApproved()
		return nil
	}
	if err := createTask(exe); err != nil {
		return err
	}
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if err := k.SetStringValue(autoName, `"`+exe+`" --startup`); err != nil {
		return err
	}
	// Turning it on in SonderShield also undoes a "Disable" from Task Manager.
	clearStartupApproved()
	return nil
}

// autostartOn: the Run entry is there, not disabled in Task Manager, and the task exists.
func (p *windowsPlatform) autostartOn() bool {
	if runEntry() == "" || startupDisabled() {
		return false
	}
	_, err := run("schtasks", "/query", "/tn", autoName)
	return err == nil
}

// fixAutostart moves older installs (task with a sign-in trigger, no Run entry) to the
// new setup, and repoints both parts if the exe moved. Called once at start, elevated.
func (p *windowsPlatform) fixAutostart(exe string) {
	entry := runEntry()
	if entry == "" {
		if _, err := run("schtasks", "/query", "/tn", autoName); err != nil {
			return // autostart is off
		}
		p.setAutostart(true, exe)
		return
	}
	if !strings.Contains(strings.ToLower(entry), strings.ToLower(exe)) || !strings.Contains(entry, "--startup") {
		disabled := startupDisabled()
		p.setAutostart(true, exe)
		if disabled { // keep the user's choice from Task Manager
			markStartupDisabled()
		}
		return
	}
	if _, err := run("schtasks", "/query", "/tn", autoName); err != nil {
		createTask(exe)
	}
}

func runEntry() string {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, _ := k.GetStringValue(autoName)
	return v
}

// Task Manager stores Enable/Disable as a 12-byte value; the first byte is odd when disabled.
func startupDisabled() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, approvedKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	b, _, err := k.GetBinaryValue(autoName)
	return err == nil && len(b) > 0 && b[0]&1 == 1
}

func clearStartupApproved() {
	if k, err := registry.OpenKey(registry.CURRENT_USER, approvedKeyPath, registry.SET_VALUE); err == nil {
		k.DeleteValue(autoName)
		k.Close()
	}
}

func markStartupDisabled() {
	if k, _, err := registry.CreateKey(registry.CURRENT_USER, approvedKeyPath, registry.SET_VALUE); err == nil {
		k.SetBinaryValue(autoName, []byte{3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})
		k.Close()
	}
}

func createTask(exe string) error {
	xml := `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Author>Verisonder</Author><Description>Starts SonderShield with full rights without a UAC prompt.</Description></RegistrationInfo>
  <Triggers />
  <Principals>
    <Principal id="Author"><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>false</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>6</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec><Command>` + xmlEscape(exe) + `</Command><Arguments>--tray</Arguments><WorkingDirectory>` + xmlEscape(filepath.Dir(exe)) + `</WorkingDirectory></Exec>
  </Actions>
</Task>`
	f := filepath.Join(os.TempDir(), "sondershield-task.xml")
	u := utf16.Encode([]rune(xml))
	b := make([]byte, 2+2*len(u))
	b[0], b[1] = 0xFF, 0xFE
	for i, c := range u {
		b[2+2*i], b[3+2*i] = byte(c), byte(c>>8)
	}
	if err := os.WriteFile(f, b, 0o600); err != nil {
		return err
	}
	defer os.Remove(f)
	_, err := run("schtasks", "/create", "/tn", autoName, "/xml", f, "/f")
	return err
}

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace(s)
}

// startupLaunch handles "--startup" (the Run entry at sign-in). Without rights it asks the
// task to start the elevated copy and returns true (this copy exits). If the task can't
// run, it returns false and the normal UAC relaunch takes over.
func startupLaunch() bool {
	if windows.GetCurrentProcessToken().IsElevated() {
		return false // already has rights (UAC off): just carry on
	}
	c := exec.Command("schtasks", "/run", "/tn", autoName)
	hideWindow(c)
	return c.Run() == nil
}
