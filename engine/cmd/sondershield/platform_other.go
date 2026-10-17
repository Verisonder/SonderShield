//go:build !windows

package main

// A no-op platform so the program builds and its logic can be tested on Linux. The real
// behaviour is in platform_windows.go.
type otherPlatform struct{ dir string }

func newPlatform() platform { return &otherPlatform{dir: portableDataDir()} }

func (p *otherPlatform) dataDir() string                        { return p.dir }
func (p *otherPlatform) setSystemDNS(addr string) error         { return nil }
func (p *otherPlatform) restoreSystemDNS() error                { return nil }
func (p *otherPlatform) setFirewall(paths []string) error       { return nil }
func (p *otherPlatform) clearFirewall() error                   { return nil }
func (p *otherPlatform) outsideInterfaces() (int, int)          { return 0, 0 }
func (p *otherPlatform) setAutostart(on bool, exe string) error { return nil }
func (p *otherPlatform) autostartOn() bool                      { return false }

func (p *otherPlatform) listPrograms() []Program { return nil }

func localeFromOS() (lang, country string) { return "en", "" }
