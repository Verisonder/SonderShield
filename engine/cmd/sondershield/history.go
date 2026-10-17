package main

import (
	"sort"
	"strings"
	"sync"
	"time"
)

// connHistory remembers connections seen over this protection session, so there's a record
// of what each app talked to, not just what's open right now (Portmaster's "Network
// History"). Kept in memory; it starts fresh each time protection turns on.

type histEntry struct {
	App       string `json:"app"`
	Path      string `json:"path"`
	Domain    string `json:"domain"`
	Remote    string `json:"remote"`
	Port      int    `json:"port"`
	Direct    bool   `json:"direct"`
	Blocked   bool   `json:"blocked"`
	Count     int    `json:"count"`
	FirstSeen int64  `json:"firstSeen"`
	LastSeen  int64  `json:"lastSeen"`
}

type connHistory struct {
	mu  sync.Mutex
	m   map[string]*histEntry
	cap int
}

func newConnHistory(cap int) *connHistory {
	return &connHistory{m: make(map[string]*histEntry), cap: cap}
}

func (h *connHistory) reset() {
	h.mu.Lock()
	h.m = make(map[string]*histEntry)
	h.mu.Unlock()
}

// record folds the current connections into the history: a repeat of the same app+target
// bumps its count and last-seen time instead of adding a new row.
func (h *connHistory) record(conns []connView) {
	now := time.Now().UnixMilli()
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, c := range conns {
		if c.App == "" && c.RemoteAddr == "" {
			continue
		}
		key := strings.ToLower(c.App) + "|" + c.RemoteAddr + "|" + c.Domain
		if e, ok := h.m[key]; ok {
			e.Count++
			e.LastSeen = now
			if c.Domain != "" {
				e.Domain = c.Domain
			}
			e.Blocked = c.Blocked
		} else {
			h.m[key] = &histEntry{
				App: c.App, Path: c.Path, Domain: c.Domain, Remote: c.RemoteAddr, Port: c.RemotePort,
				Direct: c.Direct, Blocked: c.Blocked, Count: 1, FirstSeen: now, LastSeen: now,
			}
		}
	}
	// Keep the newest `cap` rows by last-seen.
	if len(h.m) > h.cap {
		all := make([]*histEntry, 0, len(h.m))
		for _, e := range h.m {
			all = append(all, e)
		}
		sort.Slice(all, func(i, j int) bool { return all[i].LastSeen > all[j].LastSeen })
		keep := make(map[string]*histEntry, h.cap)
		for i := 0; i < h.cap && i < len(all); i++ {
			e := all[i]
			keep[strings.ToLower(e.App)+"|"+e.Remote+"|"+e.Domain] = e
		}
		h.m = keep
	}
}

// list returns the history newest-first.
func (h *connHistory) list() []histEntry {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]histEntry, 0, len(h.m))
	for _, e := range h.m {
		out = append(out, *e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastSeen > out[j].LastSeen })
	return out
}
