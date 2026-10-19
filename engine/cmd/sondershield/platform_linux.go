//go:build linux

package main

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	shield "github.com/Verisonder/SonderShield/engine"
)

// The Linux platform. Everything goes through nftables, in SonderShield's own tables (so
// nothing of firewalld/ufw is touched), and the tables disappear at reboot or on stop:
//
//	sondershield_dns   every app's DNS (port 53) redirected to SonderShield
//	sondershield_lock  Force DNS: other DNS routes closed
//	sondershield_net   your address and port blocks
//	sondershield_kill  the panic switch
//
// The service runs as root; it is the only process the redirect and Force DNS let through,
// which is how its own upstream keeps working.

const (
	stateDir   = "/var/lib/sondershield"
	runDir     = "/run/sondershield"
	installBin = "/usr/local/bin/sondershield"
	unitName   = "sondershield.service"
	unitPath   = "/etc/systemd/system/" + unitName
	polkitPath = "/etc/polkit-1/rules.d/49-sondershield.rules"

	tblDNS  = "sondershield_dns"
	tblLock = "sondershield_lock"
	tblNet  = "sondershield_net"
	tblKill = "sondershield_kill"
)

// Port 53 on Linux usually belongs to systemd-resolved (127.0.0.53) or dnsmasq, so the
// engine listens on a high port and the redirect brings DNS to it.
func init() { dnsPort = 53530 }

type linuxPlatform struct{ dir string }

func newPlatform() platform {
	if os.Geteuid() == 0 {
		os.MkdirAll(stateDir, 0o755)
		return &linuxPlatform{dir: stateDir}
	}
	return &linuxPlatform{dir: userDataDir()}
}

// userDataDir: the launcher's own folder (it keeps nothing important there).
func userDataDir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = os.TempDir()
	}
	d = filepath.Join(d, "sondershield")
	os.MkdirAll(d, 0o755)
	return d
}

// instanceDir is where the service writes its window address; the launcher reads it there.
func instanceDir(p platform) string { return runDir }

func (p *linuxPlatform) dataDir() string { return p.dir }

// ---- nftables ----

func nftPath() string {
	for _, c := range []string{"/usr/sbin/nft", "/sbin/nft", "/usr/bin/nft"} {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	if p, err := exec.LookPath("nft"); err == nil {
		return p
	}
	return ""
}

var errNoNft = errors.New("nftables (the nft command) is not installed — install the nftables package")

// nftReplace swaps one table for a new body in a single transaction (nothing half-applied).
func nftReplace(table, body string) error {
	return nftRunner("table inet " + table + "\ndelete table inet " + table + "\ntable inet " + table + " {\n" + body + "}\n")
}

// nftRunner applies a script with nft (a variable so the tests can check the scripts).
var nftRunner = func(script string) error {
	nft := nftPath()
	if nft == "" {
		return errNoNft
	}
	cmd := exec.Command(nft, "-f", "-")
	cmd.Stdin = strings.NewReader(script)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("nft: %s", strings.TrimSpace(string(out)))
	}
	return nil
}

func nftDrop(table string) {
	if nft := nftPath(); nft != "" {
		exec.Command(nft, "delete", "table", "inet", table).Run()
	}
}

func nftHas(table string) bool {
	nft := nftPath()
	return nft != "" && exec.Command(nft, "list", "table", "inet", table).Run() == nil
}

// dropRules removes everything SonderShield put in place except the panic switch, which
// is meant to stay until switched off. Used on stop and by the service's ExecStopPost.
func dropRules() {
	nftDrop(tblDNS)
	nftDrop(tblLock)
	nftDrop(tblNet)
	flushResolverCache()
}

// flushResolverCache drops answers cached before (or during) filtering.
func flushResolverCache() {
	if p, err := exec.LookPath("resolvectl"); err == nil {
		exec.Command(p, "flush-caches").Run()
	}
}

// ---- DNS ----

func (p *linuxPlatform) setSystemDNS(v4, v6 string) error {
	port := strconv.Itoa(dnsPort)
	body := `  chain out {
    type nat hook output priority -100; policy accept;
    meta skuid 0 return
    ip daddr 127.0.0.0/8 return
    ip6 daddr ::1 return
    udp dport 53 redirect to :` + port + `
    tcp dport 53 redirect to :` + port + `
  }
`
	if err := nftReplace(tblDNS, body); err != nil {
		return err
	}
	flushResolverCache()
	return nil
}

func (p *linuxPlatform) restoreSystemDNS() error {
	nftDrop(tblDNS)
	flushResolverCache()
	return nil
}

func (p *linuxPlatform) pendingRestore() bool { return nftHas(tblDNS) }

func (p *linuxPlatform) startTunDNS(e *shield.Engine) (string, string, error) {
	return "", "", fmt.Errorf("port %d is in use by %s", dnsPort, orUnknown(p.port53Owner()))
}

func orUnknown(s string) string {
	if s == "" {
		return "another program"
	}
	return s
}

// currentDNS: whether the redirect is in place (that is what sends every app to us), or
// else the servers the system resolver is configured with.
func (p *linuxPlatform) currentDNS() []AdapterDNS {
	if nftHas(tblDNS) {
		return []AdapterDNS{{Name: "Every app (redirected to SonderShield)", Servers: []string{"127.0.0.1", "::1"}, Ours: true}}
	}
	return []AdapterDNS{{Name: "System resolver", Servers: resolvConfServers()}}
}

func resolvConfServers() []string {
	f, err := os.Open("/etc/resolv.conf")
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fs := strings.Fields(sc.Text())
		if len(fs) >= 2 && fs[0] == "nameserver" {
			out = append(out, fs[1])
		}
	}
	return out
}

// ---- Force DNS ----

// setDNSLockdown closes plain DNS and DNS-over-TLS to public servers, and HTTPS to the
// known DNS-over-HTTPS resolvers, for every app. SonderShield (root) is exempt, so unlike
// Windows no provider has to be left open: browsers' Cloudflare DoH is blocked too.
func (p *linuxPlatform) setDNSLockdown(on bool, skipProvider string) error {
	if !on {
		nftDrop(tblLock)
		return nil
	}
	var doh4, doh6 []string
	for _, ips := range dohIPs {
		a, b := splitV4V6(ips)
		doh4 = append(doh4, a...)
		doh6 = append(doh6, b...)
	}
	sort.Strings(doh4)
	sort.Strings(doh6)
	set := func(name, typ string, elems []string) string {
		return "  set " + name + " { type " + typ + "; flags interval; auto-merge; elements = { " + strings.Join(elems, ", ") + " } }\n"
	}
	body := set("pub4", "ipv4_addr", strings.Split(publicV4, ",")) +
		set("pub6", "ipv6_addr", []string{publicV6}) +
		set("doh4", "ipv4_addr", doh4) +
		set("doh6", "ipv6_addr", doh6) +
		`  chain out {
    type filter hook output priority 0; policy accept;
    oifname "lo" accept
    meta skuid 0 accept
    ip daddr @pub4 udp dport 53 drop
    ip daddr @pub4 tcp dport { 53, 853 } drop
    ip6 daddr @pub6 udp dport 53 drop
    ip6 daddr @pub6 tcp dport { 53, 853 } drop
    ip daddr @doh4 meta l4proto { tcp, udp } th dport 443 drop
    ip6 daddr @doh6 meta l4proto { tcp, udp } th dport 443 drop
  }
`
	return nftReplace(tblLock, body)
}

// ---- firewall: address/port blocks, panic switch ----

// Per-app rules need a per-program firewall, which Linux doesn't have (nftables matches
// users and cgroups, not programs). The window hides that section on Linux.
func (p *linuxPlatform) setAppRules(rules []AppRule) error { return nil }

func (p *linuxPlatform) clearFirewall() error {
	nftDrop(tblNet)
	return nil
}

func (p *linuxPlatform) setNetworkBlocks(rules []NetBlock) error {
	var lines []string
	for _, nb := range rules {
		v := strings.TrimSpace(nb.Value)
		switch nb.Kind {
		case "ip":
			if !validIPOrCIDR(v) {
				continue
			}
			if strings.Contains(v, ":") {
				lines = append(lines, "    ip6 daddr "+v+" drop")
			} else {
				lines = append(lines, "    ip daddr "+v+" drop")
			}
		case "port":
			if !validPort(v) {
				continue
			}
			v = strings.ReplaceAll(v, " ", "")
			lines = append(lines, "    tcp dport "+v+" drop", "    udp dport "+v+" drop")
		}
	}
	if len(lines) == 0 {
		nftDrop(tblNet)
		return nil
	}
	body := "  chain out {\n    type filter hook output priority 0; policy accept;\n    oifname \"lo\" accept\n" +
		strings.Join(lines, "\n") + "\n  }\n"
	return nftReplace(tblNet, body)
}

// The panic switch: drop everything in and out except loopback, so the window still works.
func (p *linuxPlatform) setKillSwitch(on bool) error {
	if !on {
		nftDrop(tblKill)
		return nil
	}
	return nftReplace(tblKill, `  chain out {
    type filter hook output priority -10; policy accept;
    oifname "lo" accept
    drop
  }
  chain in {
    type filter hook input priority -10; policy accept;
    iifname "lo" accept
    drop
  }
`)
}

func (p *linuxPlatform) killSwitchOn() bool { return nftHas(tblKill) }

func (p *linuxPlatform) outsideInterfaces() (int, int) { return 0, 0 }

// ---- start with the computer: the systemd service ----

func systemctl(args ...string) error {
	out, err := exec.Command("systemctl", args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl %s: %s", strings.Join(args, " "), strings.TrimSpace(string(out)))
	}
	return nil
}

func (p *linuxPlatform) setAutostart(on bool, exe string) error {
	if on {
		return systemctl("enable", unitName)
	}
	return systemctl("disable", unitName)
}

func (p *linuxPlatform) autostartOn() bool {
	return exec.Command("systemctl", "is-enabled", "--quiet", unitName).Run() == nil
}

func (p *linuxPlatform) fixAutostart(exe string) {}

// ---- programs and connections ----

func (p *linuxPlatform) listPrograms() []Program { return nil }

func (p *linuxPlatform) listConnections() []Conn {
	procs := socketOwners()
	var out []Conn
	for _, s := range readSockets("tcp") {
		if s.state != "01" { // established only
			continue
		}
		if s.raddr.IsLoopback() || s.raddr.IsUnspecified() || s.rport == 0 {
			continue
		}
		pr := procs[s.inode]
		out = append(out, Conn{
			Proto: "TCP", State: "established", App: pr.name, Path: pr.path, PID: pr.pid,
			LocalAddr: s.laddr.String(), LocalPort: s.lport, RemoteAddr: s.raddr.String(), RemotePort: s.rport,
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].App != out[j].App {
			return strings.ToLower(out[i].App) < strings.ToLower(out[j].App)
		}
		return out[i].RemoteAddr < out[j].RemoteAddr
	})
	return out
}

func (p *linuxPlatform) connCounts() (int, int) {
	cs := p.listConnections()
	apps := map[string]bool{}
	for _, c := range cs {
		apps[c.Path+"|"+c.App] = true
	}
	return len(cs), len(apps)
}

func (p *linuxPlatform) port53Owner() string {
	procs := socketOwners()
	for _, s := range readSockets("udp") {
		if s.lport == dnsPort {
			if pr, ok := procs[s.inode]; ok && pr.name != "" {
				return pr.name
			}
		}
	}
	return ""
}

func (p *linuxPlatform) appIcon(path string) []byte { return desktopIcon(path) }

// ---- data counters ----

// rawNetCounters reads /proc/net/dev (all interfaces but loopback). The counters there are
// 64-bit; the meter expects 32-bit wrapping ones, which it handles.
func (p *linuxPlatform) rawNetCounters() []NetCounter {
	f, err := os.Open("/proc/net/dev")
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []NetCounter
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		name, rest, ok := strings.Cut(sc.Text(), ":")
		if !ok {
			continue
		}
		name = strings.TrimSpace(name)
		if name == "lo" {
			continue
		}
		fs := strings.Fields(rest)
		if len(fs) < 9 {
			continue
		}
		rx, _ := strconv.ParseUint(fs[0], 10, 64)
		tx, _ := strconv.ParseUint(fs[8], 10, 64)
		idx := 0
		if b, err := os.ReadFile("/sys/class/net/" + name + "/ifindex"); err == nil {
			idx, _ = strconv.Atoi(strings.TrimSpace(string(b)))
		}
		out = append(out, NetCounter{Index: uint32(idx), In: uint32(rx), Out: uint32(tx)})
	}
	return out
}

// ---- language and country ----

// tzCountry: the regional lists (MA/DZ/TN) follow the country; the time zone is the most
// reliable hint on a Linux desktop (the language is often en_US everywhere).
var tzCountry = map[string]string{
	"Africa/Casablanca": "MA", "Africa/El_Aaiun": "MA", "Africa/Algiers": "DZ", "Africa/Tunis": "TN",
	"Europe/Paris": "FR", "Europe/Brussels": "BE", "Europe/Zurich": "CH", "Europe/Berlin": "DE",
}

func localeFromOS() (lang, country string) {
	loc := ""
	for _, k := range []string{"LC_ALL", "LC_MESSAGES", "LANG"} {
		if v := os.Getenv(k); v != "" && v != "C" && v != "POSIX" {
			loc = v
			break
		}
	}
	if loc == "" { // the service has no session environment
		if b, err := os.ReadFile("/etc/locale.conf"); err == nil {
			for _, l := range strings.Split(string(b), "\n") {
				if v, ok := strings.CutPrefix(strings.TrimSpace(l), "LANG="); ok {
					loc = strings.Trim(v, `"`)
				}
			}
		}
	}
	loc, _, _ = strings.Cut(loc, ".")
	l, c, _ := strings.Cut(loc, "_")
	lang = strings.ToLower(l)
	if lang == "" {
		lang = "en"
	}
	country = strings.ToUpper(c)
	if tz, err := os.Readlink("/etc/localtime"); err == nil {
		if i := strings.Index(tz, "zoneinfo/"); i >= 0 {
			if cc := tzCountry[tz[i+len("zoneinfo/"):]]; cc != "" {
				country = cc
			}
		}
	}
	return lang, country
}
