// Package shield is SonderShield's filtering engine. The Android app binds it with
// gomobile; the Windows app will use it directly. Everything exported here is the
// API the apps see, so it keeps to types gomobile can carry.
package shield

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Listener gets one call per DNS query, from engine threads.
// verdict: 0 allowed, 1 blocked, 2 failed (no answer from upstream).
// uid: the Android app that asked (-1 when unknown).
type Listener interface {
	OnQuery(domain string, qtype int, verdict int, reason string, ms int, uid int)
}

// AppResolver tells which app owns a connection (Android: getConnectionOwnerUid).
// proto: 6 TCP, 17 UDP. Returns the app's uid, or -1.
type AppResolver interface {
	Uid(proto int, srcIP string, srcPort int, dstIP string, dstPort int) int
}

type Engine struct {
	filt atomic.Pointer[filter]
	up   atomic.Pointer[upstreamBox]
	lis  atomic.Pointer[listenerBox]
	apps atomic.Pointer[resolverBox]
	uids uidCache
	cos  atomic.Pointer[cosmetics] // page rules from the lists (HTTPS filtering)
	mitm atomic.Pointer[mitmConfig]

	listBlock, listAllow domainSet // last loaded lists, reused when only rules change
	listNames            []string
	userBlock, userAllow domainSet
	mu                   sync.Mutex // guards the four sets above

	cache *cache

	total, blocked atomic.Int64
	conns          atomic.Int64 // full tunnel: connections made

	wg      atomic.Pointer[wgConfig]
	testOut outbound    // tests only: where full-tunnel connections go
	testTLS *tls.Config // tests only: upstream TLS for HTTPS filtering

	runMu sync.Mutex
	run   runner
}

type upstreamBox struct{ u upstream }
type listenerBox struct{ l Listener }
type resolverBox struct{ r AppResolver }

// NewEngine makes an engine that forwards to Cloudflare over plain DNS until told otherwise.
func NewEngine() *Engine {
	e := &Engine{
		cache:     newCache(4096),
		listBlock: domainSet{}, listAllow: domainSet{},
		userBlock: domainSet{}, userAllow: domainSet{},
	}
	u, _ := parseUpstream("udp 1.1.1.1 1.0.0.1")
	e.up.Store(&upstreamBox{u})
	e.rebuild()
	return e
}

// SetLists loads block lists from files, one path per line, replacing the previous
// lists. Returns how many entries were read. A file that fails to open is skipped and
// reported in the error; the others still load. A list is named after its file
// ("stevenblack.txt" -> "stevenblack"); blocks it causes report "list:<name>".
func (e *Engine) SetLists(paths string) (int, error) {
	block, allow := domainSet{}, domainSet{}
	cos := newCosmetics()
	names := []string{""} // index 0: unnamed
	total := 0
	var errs []string
	for _, p := range strings.Split(paths, "\n") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		name := strings.TrimSuffix(filepath.Base(p), filepath.Ext(p))
		src := uint16(0)
		if len(names) < 65535 {
			src = uint16(len(names))
			names = append(names, name)
		}
		n, err := loadListFile(p, block, allow, src, cos)
		total += n
		if err != nil {
			errs = append(errs, err.Error())
		}
	}
	cos.finish()
	e.cos.Store(cos)
	e.mu.Lock()
	e.listBlock, e.listAllow, e.listNames = block, allow, names
	e.mu.Unlock()
	e.rebuild()
	if len(errs) > 0 {
		return total, errors.New(strings.Join(errs, "; "))
	}
	return total, nil
}

// CountList reads one list file and returns its entry count without loading it.
func CountList(path string) (int, error) {
	return loadListFile(path, domainSet{}, domainSet{}, 0, nil)
}

// SetRules sets your own rules, one domain per line. Allow wins over block.
func (e *Engine) SetRules(block, allow string) {
	e.mu.Lock()
	e.userBlock, e.userAllow = parseRules(block), parseRules(allow)
	e.mu.Unlock()
	e.rebuild()
}

func (e *Engine) rebuild() {
	e.mu.Lock()
	f := &filter{listBlock: e.listBlock, listAllow: e.listAllow, userBlock: e.userBlock, userAllow: e.userAllow, listNames: e.listNames}
	e.mu.Unlock()
	e.filt.Store(f)
	e.cache.clear()
}

// DomainCount is the number of distinct blocked domains loaded from lists.
func (e *Engine) DomainCount() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.listBlock)
}

// SetUpstream: "udp <ip>[:port]..." or "doh <url> <ip>...".
func (e *Engine) SetUpstream(spec string) error {
	u, err := parseUpstream(spec)
	if err != nil {
		return err
	}
	e.up.Store(&upstreamBox{u})
	e.cache.clear()
	return nil
}

func (e *Engine) SetListener(l Listener) { e.lis.Store(&listenerBox{l}) }

// SetAppResolver lets the engine name the app behind each query and connection.
func (e *Engine) SetAppResolver(r AppResolver) { e.apps.Store(&resolverBox{r}) }

// Check says what would happen to a domain: "blocked|<reason>|<matched rule>" or
// "allowed|<reason>|<matched rule>" (reason and rule empty when nothing matched).
func (e *Engine) Check(domain string) string {
	d, ok := cleanDomain(domain)
	if !ok {
		return "invalid||"
	}
	b, reason, m := e.filt.Load().decide(d)
	if b {
		return "blocked|" + reason + "|" + m
	}
	return "allowed|" + reason + "|" + m
}

// TestUpstream sends one query for example.com and returns the time in ms.
func (e *Engine) TestUpstream(spec string) (int, error) {
	u, err := parseUpstream(spec)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	t := time.Now()
	resp, err := u.exchange(ctx, buildQuery(0x5d5d, "example.com", typeA))
	if err != nil {
		return 0, err
	}
	if minTTL(resp) < 0 {
		return 0, errors.New("upstream answered with an error")
	}
	return int(time.Since(t).Milliseconds()), nil
}

func (e *Engine) Total() int64 { return e.total.Load() }

// Connections counts TCP connections relayed in full-tunnel mode.
func (e *Engine) Connections() int64 { return e.conns.Load() }
func (e *Engine) Blocked() int64     { return e.blocked.Load() }

func (e *Engine) ResetCounters() {
	e.total.Store(0)
	e.blocked.Store(0)
}

// Resolve answers one DNS query message. Used by the tunnel and by the tests.
func (e *Engine) Resolve(query []byte) []byte {
	resp, _ := e.resolve(context.Background(), query, -1)
	return resp
}

func (e *Engine) resolve(ctx context.Context, query []byte, uid int) ([]byte, bool) {
	q, err := parseQuestion(query)
	if err != nil {
		return nil, false
	}
	start := time.Now()
	e.total.Add(1)
	if q.qtype == typeHTTPS && e.mitm.Load() != nil {
		// No HTTPS records while filtering: they carry ECH keys, which would hide the
		// site's name from the filter.
		e.notify(q, verdictAllowed, "https-rr", 0, uid)
		return blockedReply(query, q), true
	}
	blocked, reason, _ := e.filt.Load().decide(q.name)
	if blocked {
		e.blocked.Add(1)
		e.notify(q, verdictBlocked, reason, 0, uid)
		return blockedReply(query, q), true
	}
	key := cacheKey(query, q)
	if c := e.cache.get(key, start); c != nil {
		r := append([]byte(nil), c...)
		setID(r, q.id)
		e.notify(q, verdictAllowed, "cached", 0, uid)
		return r, true
	}
	ctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	resp, err := e.up.Load().u.exchange(ctx, query)
	ms := int(time.Since(start).Milliseconds())
	if err != nil {
		e.notify(q, verdictFailed, err.Error(), ms, uid)
		return failReply(query, q), true
	}
	setID(resp, q.id)
	e.cache.put(key, append([]byte(nil), resp...), minTTL(resp), time.Now())
	e.notify(q, verdictAllowed, reason, ms, uid)
	return resp, true
}

func (e *Engine) notify(q question, verdict int, reason string, ms int, uid int) {
	if b := e.lis.Load(); b != nil && b.l != nil {
		b.l.OnQuery(q.name, int(q.qtype), verdict, reason, ms, uid)
	}
}

// appFor asks the app resolver who owns a flow, remembering answers for a minute.
func (e *Engine) appFor(proto int, src net.IP, srcPort int, dst net.IP, dstPort int) int {
	b := e.apps.Load()
	if b == nil || b.r == nil {
		return -1
	}
	key := uidKey{proto, src.String(), srcPort}
	if uid, ok := e.uids.get(key); ok {
		return uid
	}
	uid := b.r.Uid(proto, src.String(), srcPort, dst.String(), dstPort)
	e.uids.put(key, uid)
	return uid
}

// handlePacket turns one tunnel packet into the reply packet, or nil to drop it.
func (e *Engine) handlePacket(ctx context.Context, pkt []byte) []byte {
	p, ok := parseUDP(pkt)
	if !ok || p.dstPort != 53 {
		return nil
	}
	uid := e.appFor(17, net.IP(p.src), int(p.srcPort), net.IP(p.dst), int(p.dstPort))
	resp, ok := e.resolve(ctx, p.payload, uid)
	if !ok {
		return nil
	}
	max := 1500 - 48
	if len(resp) > max {
		q, err := parseQuestion(p.payload)
		if err != nil {
			return nil
		}
		resp = truncate(resp, q)
	}
	return p.reply(resp)
}

// runner is a running tunnel (DNS-only or full); stop ends it and closes the descriptor.
type runner interface{ stop() }

// Running is true between Start and Stop.
func (e *Engine) Running() bool {
	e.runMu.Lock()
	defer e.runMu.Unlock()
	return e.run != nil
}

// Stop ends the tunnel loop and closes the tunnel. Safe to call when not running.
func (e *Engine) Stop() {
	e.runMu.Lock()
	r := e.run
	e.run = nil
	e.runMu.Unlock()
	if r != nil {
		r.stop()
	}
}
