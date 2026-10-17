package main

import (
	"context"
	"net"
	"strings"
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
	data  *dataMeter
	hist  *connHistory
	tl    *timeline

	mu        sync.Mutex
	cfg       Config
	on        bool
	problem   string
	startedAt int64
	lastQuery int64  // ms of the most recent DNS query seen (for the "is it working" check)
	mode      string // "local" (port 53 here) or "adapter" (SonderShield network adapter)

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
		data:  newDataMeter(),
		hist:  newConnHistory(3000),
		tl:    &timeline{},
	}
	lang, country := systemLocale()
	a.cfg = defaultConfig(lang, country)
	st.load("config.json", &a.cfg)
	if a.cfg.Lists == nil {
		a.cfg.Lists = defaultLists(lang, country)
	}
	if len(a.cfg.AppRules) == 0 && len(a.cfg.Firewall) > 0 {
		for _, p := range a.cfg.Firewall {
			a.cfg.AppRules = append(a.cfg.AppRules, AppRule{Path: p, Mode: "block"})
		}
		a.cfg.Firewall = nil
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

// connView is a connection plus what SonderShield knows about it: the domain it was
// resolved from, and whether this app is firewall-blocked. A connection with no known
// domain is a direct-to-IP connection, which a DNS filter can't see into.
type connView struct {
	Conn
	Domain  string `json:"domain"`
	Blocked bool   `json:"blocked"`
	Direct  bool   `json:"direct"`
}

// connections lists the computer's open connections, each tagged with its hostname and
// whether the owning app is blocked by the firewall.
func (a *App) connections() []connView {
	cs := a.plat.listConnections()
	c := a.config()
	blocked := map[string]bool{}
	for _, r := range c.AppRules {
		if r.Mode == "" || r.Mode == "block" {
			blocked[strings.ToLower(r.Path)] = true
		}
	}
	out := make([]connView, 0, len(cs))
	for _, cn := range cs {
		dom := a.eng.RecentName(cn.RemoteAddr)
		out = append(out, connView{
			Conn: cn, Domain: dom,
			Blocked: cn.Path != "" && blocked[strings.ToLower(cn.Path)],
			Direct:  dom == "" && !isPrivateIP(cn.RemoteAddr),
		})
	}
	return out
}

// dnsDiag is the "is Windows actually using us?" picture: what we set, what each adapter
// is really using, and whether queries are arriving.
type dnsDiag struct {
	On        bool         `json:"on"`
	Mode      string       `json:"mode"`
	Expected  []string     `json:"expected"`
	Receiving bool         `json:"receiving"`
	AllGood   bool         `json:"allGood"`
	Adapters  []AdapterDNS `json:"adapters"`
	PortOwner string       `json:"portOwner"` // who holds port 53 (adapter mode)
	Seen      int64        `json:"seen"`      // lookups since protection started
}

// diag builds the DNS diagnostic shown when queries aren't arriving.
func (a *App) diag() dnsDiag {
	a.mu.Lock()
	on, mode := a.on, a.mode
	a.mu.Unlock()
	var expected []string
	switch mode {
	case "local":
		expected = []string{"127.0.0.1", "::1"}
	case "adapter":
		expected = []string{"10.111.222.53", "fd00:5d5d::53"}
	}
	ads := a.plat.currentDNS()
	allGood := on && len(ads) > 0
	for i := range ads {
		if ads[i].Ours {
			ads[i].Ok = true
			continue
		}
		for _, s := range ads[i].Servers {
			for _, e := range expected {
				if strings.EqualFold(s, e) {
					ads[i].Ok = true
				}
			}
		}
		if !ads[i].Ok {
			allGood = false
		}
	}
	owner := ""
	if mode == "adapter" {
		owner = a.plat.port53Owner()
	}
	return dnsDiag{On: on, Mode: mode, Expected: expected, Receiving: a.active(), AllGood: allGood, Adapters: ads, PortOwner: owner, Seen: a.eng.Total()}
}

// setConfig replaces settings, saves them, and re-applies to a running engine.
func (a *App) setConfig(c Config) {
	a.mu.Lock()
	a.cfg = c
	on := a.on
	a.mu.Unlock()
	a.st.save("config.json", &c)
	if on {
		a.plat.setDNSLockdown(c.LockDNS, c.Upstream)
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
	a.plat.setAppRules(c.AppRules)
	a.plat.setNetworkBlocks(c.NetBlocks)
}

func (a *App) running() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.on
}

func systemLocale() (lang, country string) { return localeFromOS() }

// isPrivateIP reports whether an address is a LAN / loopback / link-local address, which
// legitimately has no public hostname and shouldn't be flagged as a direct-IP connection.
func isPrivateIP(s string) bool {
	ip := net.ParseIP(s)
	if ip == nil {
		return true
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified()
}

// dataMeter totals bytes in/out across all adapters for this protection session. The OS
// counters are 32-bit and wrap at 4 GB, so we accumulate deltas (uint32 subtraction wraps
// correctly) into 64-bit totals. A counter reset (adapter reconnect) looks like a giant
// delta, so anything over 8 GB in one sample is treated as a reset and skipped.
type dataMeter struct {
	mu      sync.Mutex
	last    map[uint32][2]uint32
	in, out uint64
}

func newDataMeter() *dataMeter { return &dataMeter{last: map[uint32][2]uint32{}} }

func (m *dataMeter) reset() {
	m.mu.Lock()
	m.last = map[uint32][2]uint32{}
	m.in, m.out = 0, 0
	m.mu.Unlock()
}

const dataResetGuard = 8 << 30

func (m *dataMeter) sample(cs []NetCounter) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, c := range cs {
		if prev, ok := m.last[c.Index]; ok {
			if din := c.In - prev[0]; uint64(din) < dataResetGuard {
				m.in += uint64(din)
			}
			if dout := c.Out - prev[1]; uint64(dout) < dataResetGuard {
				m.out += uint64(dout)
			}
		}
		m.last[c.Index] = [2]uint32{c.In, c.Out}
	}
}

func (m *dataMeter) totals() (uint64, uint64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.in, m.out
}

// Program icons, read once per path and kept for the session (nil results too, so a
// program without an icon isn't asked again and again).
var (
	iconMu    sync.Mutex
	iconCache = map[string][]byte{}
)

func (a *App) icon(path string) []byte {
	if path == "" || !strings.HasSuffix(strings.ToLower(path), ".exe") {
		return nil
	}
	key := strings.ToLower(path)
	iconMu.Lock()
	if b, ok := iconCache[key]; ok {
		iconMu.Unlock()
		return b
	}
	iconMu.Unlock()
	b := a.plat.appIcon(path)
	iconMu.Lock()
	iconCache[key] = b
	iconMu.Unlock()
	return b
}
