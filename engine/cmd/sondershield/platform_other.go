//go:build !windows

package main

import (
	"errors"

	shield "github.com/Verisonder/SonderShield/engine"
)

// A no-op platform so the program builds and its logic can be tested on Linux. The real
// behaviour is in platform_windows.go and dns_windows.go.
type otherPlatform struct{ dir string }

func newPlatform() platform { return &otherPlatform{dir: portableDataDir()} }

func (p *otherPlatform) dataDir() string                        { return p.dir }
func (p *otherPlatform) setSystemDNS(v4, v6 string) error       { return nil }
func (p *otherPlatform) restoreSystemDNS() error                { return nil }
func (p *otherPlatform) pendingRestore() bool                   { return false }
func (p *otherPlatform) setFirewall(paths []string) error       { return nil }
func (p *otherPlatform) clearFirewall() error                   { return nil }
func (p *otherPlatform) outsideInterfaces() (int, int)          { return 0, 0 }
func (p *otherPlatform) setAutostart(on bool, exe string) error { return nil }
func (p *otherPlatform) autostartOn() bool                      { return false }
func (p *otherPlatform) listPrograms() []Program                { return nil }
func (p *otherPlatform) listConnections() []Conn                { return nil }
func (p *otherPlatform) setKillSwitch(on bool) error            { return nil }
func (p *otherPlatform) killSwitchOn() bool                     { return false }
func (p *otherPlatform) setDNSLockdown(on bool, skip string) error { return nil }
func (p *otherPlatform) startTunDNS(e *shield.Engine) (string, string, error) {
	return "", "", errors.New("no tunnel on this system")
}

func localeFromOS() (lang, country string) { return "en", "" }
