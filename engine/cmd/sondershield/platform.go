package main

import (
	"os"
	"path/filepath"

	shield "github.com/Verisonder/SonderShield/engine"
)

// platform is the handful of things that differ by operating system: where data lives,
// pointing the computer's DNS at our local resolver, and the per-program firewall. Each
// one is a thin wrapper over a standard OS tool (netsh on Windows), nothing hidden.
type platform interface {
	// dataDir is where config, lists and stats are kept.
	dataDir() string
	// setSystemDNS points every connected adapter at our DNS (IPv4 and IPv6 addresses) and
	// remembers what was there; restoreSystemDNS puts the old settings back.
	setSystemDNS(v4, v6 string) error
	restoreSystemDNS() error
	// pendingRestore: a previous run left the DNS pointed at us (crash, power cut).
	pendingRestore() bool
	// startTunDNS: serve DNS on SonderShield's own network adapter instead of port 53 on
	// this PC. Returns the DNS addresses to point Windows at.
	startTunDNS(e *shield.Engine) (v4, v6 string, err error)
	// setFirewall replaces our block rules with one per program path (full paths of .exe
	// files that should get no internet). Empty clears them.
	setFirewall(paths []string) error
	clearFirewall() error
	// outsideInterfaces: the interface indexes that reach the internet, so the engine's
	// own sockets (upstream DNS, relays) don't loop back when the full tunnel is up.
	outsideInterfaces() (v4, v6 int)
	// autostart turns "start with the computer" on or off for the current user.
	setAutostart(on bool, exe string) error
	autostartOn() bool
	// listPrograms: programs the user can pick for the firewall (running apps with a path).
	listPrograms() []Program
	// listConnections: the computer's current outbound TCP connections, with the owning app.
	listConnections() []Conn
	// setKillSwitch cuts off (or restores) all internet for the whole computer. Loopback
	// stays up, so SonderShield itself keeps working to switch it back off.
	setKillSwitch(on bool) error
	killSwitchOn() bool
	// setDNSLockdown blocks every DNS route except SonderShield (plain DNS, DoT, and known
	// DoH resolvers), so apps and browsers can't use their own DNS to skip filtering.
	// skipProvider is the upstream id whose servers must stay reachable for our own DNS.
	setDNSLockdown(on bool, skipProvider string) error
}

// Conn is one outbound connection the computer currently has open.
type Conn struct {
	Proto      string `json:"proto"`
	State      string `json:"state"`
	App        string `json:"app"`
	Path       string `json:"path"`
	PID        int    `json:"pid"`
	LocalAddr  string `json:"localAddr"`
	LocalPort  int    `json:"localPort"`
	RemoteAddr string `json:"remoteAddr"`
	RemotePort int    `json:"remotePort"`
}

// Program is one app the firewall can block.
type Program struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// portableDataDir: a "data" folder next to the .exe, or a temp fallback. The service uses
// a fixed ProgramData path instead (set in main).
func portableDataDir() string {
	exe, err := os.Executable()
	if err == nil {
		d := filepath.Join(filepath.Dir(exe), "data")
		if os.MkdirAll(d, 0o755) == nil {
			return d
		}
	}
	d := filepath.Join(os.TempDir(), "SonderShield")
	os.MkdirAll(d, 0o755)
	return d
}
