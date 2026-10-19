package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Config is everything the user sets. Saved as config.json in the data folder.
type Config struct {
	On             bool         `json:"on"`
	Lists          []string     `json:"lists"`
	Custom         []customList `json:"custom"`
	Upstream       string       `json:"upstream"`
	CustomUpstream string       `json:"customUpstream"`
	Block          string       `json:"block"`
	Allow          string       `json:"allow"`
	FullTunnel     bool         `json:"fullTunnel"`
	LockDNS        bool         `json:"lockDns"`   // force all DNS through SonderShield
	NetBlocks      []NetBlock   `json:"netBlocks"` // block by IP/CIDR or port
	Firewall       []string     `json:"firewall"`  // legacy: blocked program paths (migrated to AppRules)
	AppRules       []AppRule    `json:"appRules"`  // per-app firewall mode
	UpdateHours    int          `json:"updateHours"`
	Theme          string       `json:"theme"`      // default, light, system
	Accent         uint32       `json:"accent"`     // ARGB
	Lang           string       `json:"lang"`       // "" follows Windows, else en / fr / de
	Profile        string       `json:"profile"`    // chosen profile id; "" = your own list picks
	Profiles       []Profile    `json:"profiles"`   // profiles you made
	Schedules      []Schedule   `json:"schedules"`  // switch profile at set times
	EggBest        int          `json:"eggBest"`    // Shield-Man best score
	EggNoNames     bool         `json:"eggNoNames"` // Shield-Man: hide the enemies' names
	Autostart      bool         `json:"autostart"`
	Onboarded      bool         `json:"onboarded"`
	UpdateNotified string       `json:"updateNotified,omitempty"` // last version the "new version" notice was shown for
}

// AppRule is how one app is firewalled: Mode is "block" (no internet), "in" (block
// incoming only) or "lan" (no local-network access).
type AppRule struct {
	Path   string `json:"path"`
	Mode   string `json:"mode"`
	Edited int64  `json:"edited"` // ms; for "Recently edited"
}

type customList struct {
	Name string `json:"name"`
	URL  string `json:"url"`
}

func defaultConfig(lang, country string) Config {
	return Config{
		Lists:       defaultLists(lang, country),
		Upstream:    defaultUpstream,
		Profile:     "default",
		UpdateHours: 24,
		Theme:       "default",
		Accent:      0xFF3B82FF,
	}
}

func (c *Config) allLists() []listDef {
	out := append([]listDef(nil), catalog...)
	for _, cl := range c.Custom {
		out = append(out, listDef{ID: customID(cl.URL), Name: cl.Name, URL: cl.URL, About: cl.URL, Group: "custom", Custom: true})
	}
	return out
}

func customID(url string) string {
	var h uint32 = 2166136261
	for i := 0; i < len(url); i++ {
		h ^= uint32(url[i])
		h *= 16777619
	}
	const hex = "0123456789abcdef"
	b := []byte("custom-")
	for i := 7; i >= 0; i-- {
		b = append(b, hex[(h>>(uint(i)*4))&15])
	}
	return string(b)
}

func (c *Config) upstreamSpec() string {
	if c.Upstream == "custom" && c.CustomUpstream != "" {
		return c.CustomUpstream
	}
	if u := upstreamByID(c.Upstream); u != nil {
		return u.Spec
	}
	return upstreamByID(defaultUpstream).Spec
}

type store struct {
	dir string
	mu  sync.Mutex
}

func (s *store) path(name string) string { return filepath.Join(s.dir, name) }

func (s *store) load(name string, v any) error {
	b, err := os.ReadFile(s.path(name))
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

// hasKey reports whether the saved JSON object has this top-level key.
func (s *store) hasKey(name, key string) bool {
	var m map[string]json.RawMessage
	if s.load(name, &m) != nil {
		return false
	}
	_, ok := m[key]
	return ok
}

// save writes a temp file then renames it, so a crash never leaves half a file.
func (s *store) save(name string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tmp := s.path(name + ".tmp")
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path(name))
}

func contains(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}
