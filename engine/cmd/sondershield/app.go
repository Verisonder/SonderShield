package main

import (
	"context"
	"sync"
	"time"

	shield "github.com/Verisonder/SonderShield/engine"
)

// dnsPort is where the local resolver listens. 53 is the standard DNS port, so Windows can
// be pointed straight at 127.0.0.1 with no extra configuration (needs admin to bind).
var dnsPort = 53

type logEntry struct {
	Seq     int64  `json:"seq"`
	Time    int64  `json:"time"` // ms
	Domain  string `json:"domain"`
	Type    int    `json:"type"`
	Verdict int    `json:"verdict"` // 0 allowed, 1 blocked, 2 failed
	Reason  string `json:"reason"`
	Ms      int    `json:"ms"`
}

// App ties the engine, settings and the operating system together. One per process.
type App struct {
	plat  platform
	st    *store
	lists *listStore
	stats *stats
	eng   *shield.Engine

	mu        sync.Mutex
	cfg       Config
	on        bool
	problem   string
	startedAt int64
	lastQuery int64 // ms of the most recent DNS query seen (for the "is it working" check)

	logMu  sync.Mutex
	log    []logEntry
	logSeq int64

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

const logMax = 2000

func newApp(plat platform) *App {
	st := &store{dir: plat.dataDir()}
	a := &App{
		plat:  plat,
		st:    st,
		lists: newListStore(plat.dataDir(), st),
		stats: newStats(),
		eng:   shield.NewEngine(),
	}
	lang, country := systemLocale()
	a.cfg = defaultConfig(lang, country)
	st.load("config.json", &a.cfg)
	if a.cfg.Lists == nil {
		a.cfg.Lists = defaultLists(lang, country)
	}
	st.load("stats.json", a.stats)
	a.eng.SetListener(listener{a})
	return a
}

// listener records every DNS query: into the log ring and the stats.
type listener struct{ a *App }

func (l listener) OnQuery(domain string, qtype, verdict int, reason string, ms, uid int) {
	a := l.a
	now := time.Now()
	a.mu.Lock()
	a.lastQuery = now.UnixMilli()
	a.mu.Unlock()

	a.logMu.Lock()
	a.logSeq++
	e := logEntry{Seq: a.logSeq, Time: now.UnixMilli(), Domain: domain, Type: qtype, Verdict: verdict, Reason: reason, Ms: ms}
	a.log = append(a.log, e)
	if len(a.log) > logMax {
		a.log = a.log[len(a.log)-logMax:]
	}
	a.logMu.Unlock()

	if verdict != 2 {
		threat := len(reason) > 5 && reason[:5] == "list:" && threatLists[reason[5:]]
		a.stats.record(domain, verdict == 1, threat, now)
	}
}

// config returns a copy for readers.
func (a *App) config() Config {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.cfg
}

// setConfig replaces settings, saves them, and re-applies to a running engine.
func (a *App) setConfig(c Config) {
	a.mu.Lock()
	a.cfg = c
	on := a.on
	a.mu.Unlock()
	a.st.save("config.json", &c)
	if on {
		// Download any list just switched on that we don't have yet, then reload.
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
			defer cancel()
			for _, l := range c.allLists() {
				if contains(c.Lists, l.ID) && !a.lists.has(l.ID) {
					a.lists.update(ctx, l, false)
				}
			}
			a.applyConfig()
		}()
	}
}

// applyConfig pushes rules, upstream and lists from settings into the engine.
func (a *App) applyConfig() {
	c := a.config()
	a.eng.SetRules(c.Block, c.Allow)
	if err := a.eng.SetUpstream(c.upstreamSpec()); err != nil {
		a.eng.SetUpstream(upstreamByID(defaultUpstream).Spec)
	}
	var paths string
	for _, l := range c.allLists() {
		if contains(c.Lists, l.ID) && a.lists.has(l.ID) {
			paths += a.lists.file(l.ID) + "\n"
		}
	}
	a.eng.SetLists(paths)
	a.plat.setFirewall(c.Firewall)
}

func (a *App) running() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.on
}

func systemLocale() (lang, country string) { return localeFromOS() }
