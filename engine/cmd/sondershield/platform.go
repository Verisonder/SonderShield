package main

import (
	"os"
	"path/filepath"
)

// platform is the handful of things that differ by operating system: where data lives,
// pointing the computer's DNS at our local resolver, and the per-program firewall. Each
// one is a thin wrapper over a standard OS tool (netsh on Windows), nothing hidden.
type platform interface {
	// dataDir is where config, lists and stats are kept.
	dataDir() string
	// setSystemDNS points every active network adapter at addr (our local server) and
	// remembers what was there; restore() puts the old settings back.
	setSystemDNS(addr string) error
	restoreSystemDNS() error
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
