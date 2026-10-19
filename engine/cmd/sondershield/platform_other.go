//go:build !windows

package main

import (
	"errors"

	shield "github.com/Verisonder/SonderShield/engine"
)

// A no-op platform: the tests use it on Linux (no root, nothing touched), and it is the
// platform on systems with no real one. The real ones are platform_windows.go and
// platform_linux.go.
type otherPlatform struct{ dir string }

func (p *otherPlatform) dataDir() string                           { return p.dir }
func (p *otherPlatform) setSystemDNS(v4, v6 string) error          { return nil }
func (p *otherPlatform) restoreSystemDNS() error                   { return nil }
func (p *otherPlatform) pendingRestore() bool                      { return false }
func (p *otherPlatform) setAppRules(rules []AppRule) error         { return nil }
func (p *otherPlatform) clearFirewall() error                      { return nil }
func (p *otherPlatform) outsideInterfaces() (int, int)             { return 0, 0 }
func (p *otherPlatform) setAutostart(on bool, exe string) error    { return nil }
func (p *otherPlatform) autostartOn() bool                         { return false }
func (p *otherPlatform) fixAutostart(exe string)                   {}
func (p *otherPlatform) listPrograms() []Program                   { return nil }
func (p *otherPlatform) listConnections() []Conn                   { return nil }
func (p *otherPlatform) setKillSwitch(on bool) error               { return nil }
func (p *otherPlatform) killSwitchOn() bool                        { return false }
func (p *otherPlatform) setDNSLockdown(on bool, skip string) error { return nil }
func (p *otherPlatform) setNetworkBlocks(rules []NetBlock) error   { return nil }
func (p *otherPlatform) currentDNS() []AdapterDNS                  { return nil }
func (p *otherPlatform) rawNetCounters() []NetCounter              { return nil }
func (p *otherPlatform) connCounts() (int, int)                    { return 0, 0 }
func (p *otherPlatform) appIcon(path string) []byte                { return nil }
func (p *otherPlatform) port53Owner() string                       { return "" }
func (p *otherPlatform) startTunDNS(e *shield.Engine) (string, string, error) {
	return "", "", errors.New("no tunnel on this system")
}
