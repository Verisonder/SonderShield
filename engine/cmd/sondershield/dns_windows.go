//go:build windows

package main

import (
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unsafe"

	shield "github.com/Verisonder/SonderShield/engine"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// Pointing Windows' DNS at SonderShield, and putting it back exactly as it was.
//
// What each adapter had is written to dns-restore.json before anything changes, so the
// settings come back even if SonderShield crashes or the PC loses power while it's on.

const tunName = "SonderShield"

// The address the SonderShield adapter answers DNS on (same as the phone app).
const (
	tunAddr4 = "10.111.222.1"
	tunDNS4  = "10.111.222.53"
	tunAddr6 = "fd00:5d5d::1"
	tunDNS6  = "fd00:5d5d::53"
)

type adapterDNS struct {
	Index   int      `json:"index"`
	Name    string   `json:"name"`
	GUID    string   `json:"guid"`
	Static  []string `json:"static"`  // IPv4 servers typed in by hand ("" = from DHCP)
	Static6 []string `json:"static6"` // IPv6, same
}

type adapterInfo struct {
	index int
	name  string
	guid  string
}

// physicalAdapters: connected adapters that reach the internet (have a gateway), not ours.
func physicalAdapters() []adapterInfo {
	var size uint32 = 16 << 10
	for tries := 0; tries < 3; tries++ {
		buf := make([]byte, size)
		aa := (*windows.IpAdapterAddresses)(unsafe.Pointer(&buf[0]))
		err := windows.GetAdaptersAddresses(windows.AF_UNSPEC, windows.GAA_FLAG_INCLUDE_GATEWAYS, 0, aa, &size)
		if err == windows.ERROR_BUFFER_OVERFLOW {
			continue
		}
		if err != nil {
			return nil
		}
		var out []adapterInfo
		for a := aa; a != nil; a = a.Next {
			name := windows.UTF16PtrToString(a.FriendlyName)
			if a.OperStatus != windows.IfOperStatusUp || a.IfType == windows.IF_TYPE_SOFTWARE_LOOPBACK ||
				strings.EqualFold(name, tunName) || a.FirstGatewayAddress == nil {
				continue
			}
			idx := int(a.IfIndex)
			if idx == 0 {
				idx = int(a.Ipv6IfIndex)
			}
			out = append(out, adapterInfo{index: idx, name: name, guid: windows.BytePtrToString(a.AdapterName)})
		}
		return out
	}
	return nil
}

// staticServers reads the DNS servers typed in by hand for an adapter (empty = DHCP).
func staticServers(guid string, v6 bool) []string {
	path := `SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\` + guid
	if v6 {
		path = `SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters\Interfaces\` + guid
	}
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, path, registry.QUERY_VALUE)
	if err != nil {
		return nil
	}
	defer k.Close()
	v, _, err := k.GetStringValue("NameServer")
	if err != nil {
		return nil
	}
	return strings.FieldsFunc(v, func(r rune) bool { return r == ',' || r == ' ' })
}

func (p *windowsPlatform) restoreFile() string { return filepath.Join(p.dir, "dns-restore.json") }

func (p *windowsPlatform) setSystemDNS(v4, v6 string) error {
	ads := physicalAdapters()
	if len(ads) == 0 {
		return errors.New("no connected network adapter found")
	}
	// Remember what was there, unless an earlier run already did (then that file holds the
	// real originals, and the adapters currently point at us).
	var saved []adapterDNS
	if b, err := os.ReadFile(p.restoreFile()); err == nil {
		json.Unmarshal(b, &saved)
	}
	known := map[int]bool{}
	for _, s := range saved {
		known[s.Index] = true
	}
	for _, a := range ads {
		if !known[a.index] {
			saved = append(saved, adapterDNS{Index: a.index, Name: a.name, GUID: a.guid,
				Static: staticServers(a.guid, false), Static6: staticServers(a.guid, true)})
		}
	}
	b, _ := json.MarshalIndent(saved, "", "  ")
	if err := os.WriteFile(p.restoreFile(), b, 0o600); err != nil {
		return err
	}
	var done int
	var errs []string
	for _, a := range ads {
		idx := strconv.Itoa(a.index)
		if _, err := run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+idx, "source=static", "address="+v4, "register=none", "validate=no"); err != nil {
			errs = append(errs, a.name+": "+err.Error())
			continue
		}
		run("netsh", "interface", "ipv6", "set", "dnsservers", "name="+idx, "source=static", "address="+v6, "register=none", "validate=no")
		done++
	}
	run("ipconfig", "/flushdns")
	if done == 0 {
		return errors.New(strings.Join(errs, "; "))
	}
	return nil
}

func (p *windowsPlatform) restoreSystemDNS() error {
	b, err := os.ReadFile(p.restoreFile())
	if err != nil {
		return nil // nothing was changed
	}
	var saved []adapterDNS
	json.Unmarshal(b, &saved)
	for _, s := range saved {
		idx := strconv.Itoa(s.Index)
		restoreFamily("ipv4", idx, s.Static)
		restoreFamily("ipv6", idx, s.Static6)
	}
	run("ipconfig", "/flushdns")
	return os.Remove(p.restoreFile())
}

func restoreFamily(fam, idx string, servers []string) {
	if len(servers) == 0 {
		run("netsh", "interface", fam, "set", "dnsservers", "name="+idx, "source=dhcp")
		return
	}
	run("netsh", "interface", fam, "set", "dnsservers", "name="+idx, "source=static", "address="+servers[0], "register=primary", "validate=no")
	for i, sv := range servers[1:] {
		run("netsh", "interface", fam, "add", "dnsservers", "name="+idx, "address="+sv, "index="+strconv.Itoa(i+2), "validate=no")
	}
}

// pendingRestore: an earlier run changed the DNS and didn't put it back (crash, power cut).
func (p *windowsPlatform) pendingRestore() bool {
	_, err := os.Stat(p.restoreFile())
	return err == nil
}

//go:embed wintun/wintun.dll
var wintunDLL []byte

// startTunDNS brings up the SonderShield network adapter (WinTun) and answers DNS on it.
// Used when port 53 on this PC is taken by something else.
func (p *windowsPlatform) startTunDNS(e *shield.Engine) (string, string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", "", err
	}
	dll := filepath.Join(filepath.Dir(exe), "wintun.dll")
	if fi, err := os.Stat(dll); err != nil || fi.Size() != int64(len(wintunDLL)) {
		if len(wintunDLL) < 1024 {
			return "", "", errors.New("this build has no wintun.dll")
		}
		if err := os.WriteFile(dll, wintunDLL, 0o644); err != nil {
			return "", "", fmt.Errorf("could not place wintun.dll: %v", err)
		}
	}
	if err := e.StartTun(tunName, 1500); err != nil {
		return "", "", fmt.Errorf("could not create the SonderShield adapter: %v", err)
	}
	var ifc *net.Interface
	for i := 0; i < 30 && ifc == nil; i++ {
		ifc, _ = net.InterfaceByName(tunName)
		if ifc == nil {
			time.Sleep(100 * time.Millisecond)
		}
	}
	if ifc == nil {
		e.Stop()
		return "", "", errors.New("the SonderShield adapter didn't appear")
	}
	idx := strconv.Itoa(ifc.Index)
	if _, err := run("netsh", "interface", "ipv4", "set", "address", "name="+idx, "source=static", "address="+tunAddr4, "mask=255.255.255.0"); err != nil {
		e.Stop()
		return "", "", err
	}
	run("netsh", "interface", "ipv6", "add", "address", "interface="+idx, "address="+tunAddr6+"/64", "store=active")
	run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+idx, "source=static", "address="+tunDNS4, "register=none", "validate=no")
	return tunDNS4, tunDNS6, nil
}

// Start with Windows: a scheduled task that runs at sign-in with full rights, so there's
// no UAC prompt each time (a plain Run key can't start an elevated program).
func (p *windowsPlatform) setAutostart(on bool, exe string) error {
	// Clean up the Run key the first Windows version used.
	if k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE); err == nil {
		k.DeleteValue("SonderShield")
		k.Close()
	}
	if !on {
		run("schtasks", "/delete", "/tn", "SonderShield", "/f")
		return nil
	}
	_, err := run("schtasks", "/create", "/tn", "SonderShield", "/tr", `"`+exe+`" --tray`,
		"/sc", "onlogon", "/rl", "highest", "/f")
	return err
}

func (p *windowsPlatform) autostartOn() bool {
	_, err := run("schtasks", "/query", "/tn", "SonderShield")
	return err == nil
}
