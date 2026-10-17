//go:build windows

package main

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// windowsPlatform drives the system through netsh (DNS and firewall) and the registry
// (start with Windows), the same tools an administrator would use by hand.
type windowsPlatform struct {
	dir  string
	rule string // firewall rule name prefix
}

func newPlatform() platform {
	// One data folder for the installed and the portable app: %ProgramData%\SonderShield.
	if pd := os.Getenv("ProgramData"); pd != "" {
		d := filepath.Join(pd, "SonderShield")
		if os.MkdirAll(d, 0o755) == nil {
			if exe, err := os.Executable(); err == nil {
				migrateOldData(filepath.Join(filepath.Dir(exe), "data"), d)
			}
			return &windowsPlatform{dir: d, rule: "SonderShield"}
		}
	}
	return &windowsPlatform{dir: portableDataDir(), rule: "SonderShield"}
}

// migrateOldData copies settings from 1.1.0's "data" folder next to the exe, once.
func migrateOldData(old, dst string) {
	if old == dst {
		return
	}
	if _, err := os.Stat(filepath.Join(dst, "config.json")); err == nil {
		return
	}
	for _, n := range []string{"config.json", "stats.json", "lists.json"} {
		if b, err := os.ReadFile(filepath.Join(old, n)); err == nil {
			os.WriteFile(filepath.Join(dst, n), b, 0o600)
		}
	}
	if ents, err := os.ReadDir(old); err == nil {
		for _, e := range ents {
			if filepath.Ext(e.Name()) == ".txt" {
				if b, err := os.ReadFile(filepath.Join(old, e.Name())); err == nil {
					os.WriteFile(filepath.Join(dst, e.Name()), b, 0o644)
				}
			}
		}
	}
}

func (p *windowsPlatform) dataDir() string { return p.dir }

func hideWindow(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}

func run(name string, args ...string) (string, error) {
	c := exec.Command(name, args...)
	hideWindow(c)
	out, err := c.CombinedOutput()
	if err != nil {
		return string(out), fmt.Errorf("%s: %v: %s", name, err, strings.TrimSpace(string(out)))
	}
	return string(out), nil
}

func (p *windowsPlatform) setAppRules(rules []AppRule) error {
	p.clearFirewall()
	name := p.rule + " Block"
	var errs []string
	block := func(dir, path string, extra ...string) {
		args := []string{"advfirewall", "firewall", "add", "rule", "name=" + name,
			"dir=" + dir, "action=block", "program=" + path, "enable=yes"}
		args = append(args, extra...)
		if _, err := run("netsh", args...); err != nil {
			errs = append(errs, err.Error())
		}
	}
	for _, r := range rules {
		if r.Path == "" {
			continue
		}
		switch r.Mode {
		case "allow": // remembered as edited, but no rule
			continue
		case "in": // block only incoming connections to this app
			block("in", r.Path)
		case "lan": // block this app from the local network, in and out
			block("out", r.Path, "remoteip=LocalSubnet")
			block("in", r.Path, "remoteip=LocalSubnet")
		default: // "block": no internet at all
			block("out", r.Path)
			block("in", r.Path)
		}
	}
	if len(errs) > 0 {
		return fmt.Errorf("%s", strings.Join(errs, "; "))
	}
	return nil
}

func (p *windowsPlatform) clearFirewall() error {
	run("netsh", "advfirewall", "firewall", "delete", "rule", "name="+p.rule+" Block")
	run("netsh", "advfirewall", "firewall", "delete", "rule", "name="+p.netName())
	return nil
}

func (p *windowsPlatform) outsideInterfaces() (int, int) {
	return ifaceIndexTo("udp4", "1.1.1.1:53"), ifaceIndexTo("udp6", "[2606:4700:4700::1111]:53")
}

// ifaceIndexTo finds which adapter the system would use to reach addr, by opening a socket
// to it (no packets are sent for UDP) and matching the chosen local IP to an interface.
func ifaceIndexTo(network, addr string) int {
	c, err := net.Dial(network, addr)
	if err != nil {
		return 0
	}
	defer c.Close()
	la, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return 0
	}
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.Equal(la.IP) {
				return ifc.Index
			}
		}
	}
	return 0
}

// listPrograms lists running apps (name and full .exe path), deduplicated. These are the
// candidates for the firewall; the window shows them for the user to pick.
func (p *windowsPlatform) listPrograms() []Program {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	seen := map[string]bool{}
	var out []Program
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		name := windows.UTF16ToString(e.ExeFile[:])
		low := strings.ToLower(name)
		if e.ProcessID == 0 || seen[low] || isSystemExe(low) {
			continue
		}
		path := processPath(e.ProcessID)
		if path == "" {
			continue
		}
		seen[low] = true
		out = append(out, Program{Name: strings.TrimSuffix(name, ".exe"), Path: path})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func processPath(pid uint32) string {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(h)
	buf := make([]uint16, windows.MAX_PATH)
	n := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(h, 0, &buf[0], &n); err != nil {
		return ""
	}
	return windows.UTF16ToString(buf[:n])
}

// isSystemExe hides core Windows processes the user shouldn't cut off.
func isSystemExe(name string) bool {
	switch name {
	case "system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "services.exe",
		"lsass.exe", "winlogon.exe", "svchost.exe", "fontdrvhost.exe", "dwm.exe",
		"explorer.exe", "sihost.exe", "taskhostw.exe", "ctfmon.exe", "runtimebroker.exe",
		"sondershield.exe", "memory compression", "spoolsv.exe", "searchindexer.exe":
		return true
	}
	return false
}

// localeFromOS reads the user's locale (e.g. "fr-FR") from the registry.
func localeFromOS() (lang, country string) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Control Panel\International`, registry.QUERY_VALUE)
	if err != nil {
		return "en", ""
	}
	defer k.Close()
	v, _, err := k.GetStringValue("LocaleName")
	if err != nil {
		return "en", ""
	}
	if i := strings.IndexByte(v, '-'); i > 0 {
		return strings.ToLower(v[:i]), strings.ToUpper(v[i+1:])
	}
	return strings.ToLower(v), ""
}

// The panic switch: one "block all outbound" firewall rule (and one for inbound). Windows
// treats a block rule as higher priority than any allow rule, and never filters loopback,
// so SonderShield's own UI on 127.0.0.1 keeps working while everything else is cut off.
func (p *windowsPlatform) killName() string { return p.rule + " KillSwitch" }

func (p *windowsPlatform) setKillSwitch(on bool) error {
	run("netsh", "advfirewall", "firewall", "delete", "rule", "name="+p.killName())
	if !on {
		return nil
	}
	if _, err := run("netsh", "advfirewall", "firewall", "add", "rule",
		"name="+p.killName(), "dir=out", "action=block", "enable=yes", "profile=any"); err != nil {
		return err
	}
	run("netsh", "advfirewall", "firewall", "add", "rule",
		"name="+p.killName(), "dir=in", "action=block", "enable=yes", "profile=any")
	return nil
}

func (p *windowsPlatform) killSwitchOn() bool {
	_, err := run("netsh", "advfirewall", "firewall", "show", "rule", "name="+p.killName())
	return err == nil
}

// Network blocks: outbound block rules for raw addresses and ports, applied to every app.
func (p *windowsPlatform) netName() string { return p.rule + " Net" }

func (p *windowsPlatform) setNetworkBlocks(rules []NetBlock) error {
	run("netsh", "advfirewall", "firewall", "delete", "rule", "name="+p.netName())
	for _, nb := range rules {
		switch nb.Kind {
		case "ip":
			if !validIPOrCIDR(nb.Value) {
				continue
			}
			run("netsh", "advfirewall", "firewall", "add", "rule", "name="+p.netName(),
				"dir=out", "action=block", "enable=yes", "remoteip="+nb.Value)
		case "port":
			if !validPort(nb.Value) {
				continue
			}
			for _, proto := range []string{"TCP", "UDP"} {
				run("netsh", "advfirewall", "firewall", "add", "rule", "name="+p.netName(),
					"dir=out", "action=block", "enable=yes", "protocol="+proto, "remoteport="+nb.Value)
			}
		}
	}
	return nil
}
