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
	dir   string
	rule  string // firewall rule name prefix
	setV4 []string
	setV6 []string
}

func newPlatform() platform {
	// Data lives in %ProgramData%\SonderShield when running as a service, else next to the exe.
	dir := portableDataDir()
	if pd := os.Getenv("ProgramData"); pd != "" {
		if _, err := os.Stat(filepath.Join(pd, "SonderShield", "service")); err == nil {
			dir = filepath.Join(pd, "SonderShield")
		}
	}
	return &windowsPlatform{dir: dir, rule: "SonderShield"}
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

// activeAdapters: names of connected, non-virtual adapters that have a gateway.
func activeAdapters() []string {
	ifaces, _ := net.Interfaces()
	var names []string
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		low := strings.ToLower(ifc.Name)
		if strings.Contains(low, "sondershield") || strings.Contains(low, "loopback") {
			continue
		}
		addrs, _ := ifc.Addrs()
		hasIP := false
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.IsGlobalUnicast() {
				hasIP = true
			}
		}
		if hasIP {
			names = append(names, ifc.Name)
		}
	}
	return names
}

func (p *windowsPlatform) setSystemDNS(addr string) error {
	p.setV4 = p.setV4[:0]
	p.setV6 = p.setV6[:0]
	var errs []string
	for _, name := range activeAdapters() {
		if _, err := run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+name, "static", addr, "primary", "no"); err != nil {
			errs = append(errs, err.Error())
			continue
		}
		p.setV4 = append(p.setV4, name)
	}
	if len(p.setV4) == 0 {
		return fmt.Errorf("could not set DNS on any adapter: %s", strings.Join(errs, "; "))
	}
	// Flush the resolver cache so new lookups go to us at once.
	run("ipconfig", "/flushdns")
	return nil
}

func (p *windowsPlatform) restoreSystemDNS() error {
	for _, name := range p.setV4 {
		run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+name, "dhcp")
	}
	for _, name := range p.setV6 {
		run("netsh", "interface", "ipv6", "set", "dnsservers", "name="+name, "dhcp")
	}
	p.setV4, p.setV6 = nil, nil
	run("ipconfig", "/flushdns")
	return nil
}

func (p *windowsPlatform) setFirewall(paths []string) error {
	p.clearFirewall()
	var errs []string
	for _, path := range paths {
		if path == "" {
			continue
		}
		for _, dir := range []string{"out", "in"} {
			if _, err := run("netsh", "advfirewall", "firewall", "add", "rule",
				"name="+p.rule+" Block", "dir="+dir, "action=block", "program="+path, "enable=yes"); err != nil {
				errs = append(errs, err.Error())
			}
		}
	}
	if len(errs) > 0 {
		return fmt.Errorf("%s", strings.Join(errs, "; "))
	}
	return nil
}

func (p *windowsPlatform) clearFirewall() error {
	run("netsh", "advfirewall", "firewall", "delete", "rule", "name="+p.rule+" Block")
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

func (p *windowsPlatform) setAutostart(on bool, exe string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if on {
		return k.SetStringValue("SonderShield", `"`+exe+`" --tray`)
	}
	k.DeleteValue("SonderShield")
	return nil
}

func (p *windowsPlatform) autostartOn() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue("SonderShield")
	return err == nil
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
