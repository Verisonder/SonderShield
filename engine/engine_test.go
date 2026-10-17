package shield

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// fakeUp answers every A query with 93.184.216.34, TTL 300, and counts calls.
type fakeUp struct {
	mu    sync.Mutex
	calls int
}

func (f *fakeUp) String() string { return "fake" }
func (f *fakeUp) exchange(ctx context.Context, q []byte) ([]byte, error) {
	f.mu.Lock()
	f.calls++
	f.mu.Unlock()
	return answerA(q), nil
}

func answerA(q []byte) []byte {
	qq, _ := parseQuestion(q)
	out := append([]byte(nil), q[:qq.end]...)
	out[2] |= 0x80
	out[3] = 0x80
	binary.BigEndian.PutUint16(out[6:8], 1)
	binary.BigEndian.PutUint16(out[10:12], 0)
	out = append(out, 0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 1, 44, 0, 4, 93, 184, 216, 34)
	return out
}

func newTestEngine(t *testing.T, list string) (*Engine, *fakeUp) {
	t.Helper()
	e := NewEngine()
	p := filepath.Join(t.TempDir(), "list.txt")
	os.WriteFile(p, []byte(list), 0o644)
	if _, err := e.SetLists(p); err != nil {
		t.Fatal(err)
	}
	f := &fakeUp{}
	e.up.Store(&upstreamBox{f})
	return e, f
}

const sampleList = `# hosts style
0.0.0.0 ads.example.com
127.0.0.1 tracker.test.org other.test.org
0.0.0.0 localhost
0.0.0.0 0.0.0.0
! adblock style
||doubleclick.net^
||pixel.site.com^$important
||withpath.com/ads.js
||client.com^$client=1.2.3.4
@@||good.doubleclick.net^
example.org##.banner
plain-domain.net
*.wild.io
bad domain with spaces
`

func TestParseList(t *testing.T) {
	block, allow := domainSet{}, domainSet{}
	n, err := parseList(strings.NewReader(sampleList), block, allow)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"ads.example.com", "tracker.test.org", "other.test.org", "doubleclick.net",
		"pixel.site.com", "plain-domain.net", "wild.io"}
	for _, w := range want {
		if _, ok := block[w]; !ok {
			t.Errorf("missing %s", w)
		}
	}
	for _, no := range []string{"localhost", "withpath.com", "client.com", "example.org"} {
		if _, ok := block[no]; ok {
			t.Errorf("should not have %s", no)
		}
	}
	if _, ok := allow["good.doubleclick.net"]; !ok {
		t.Error("missing allow rule")
	}
	if n != len(want)+1 {
		t.Errorf("count %d, want %d", n, len(want)+1)
	}
}

func TestDecide(t *testing.T) {
	e, _ := newTestEngine(t, sampleList)
	e.SetRules("myblock.com\n# comment\n*.extra.net", "ads.example.com")
	cases := map[string]string{
		"x.y.doubleclick.net":  "blocked|block list|doubleclick.net",
		"good.doubleclick.net": "allowed|list exception|good.doubleclick.net",
		"ads.example.com":      "allowed|your allow rule|ads.example.com",
		"sub.ads.example.com":  "allowed|your allow rule|ads.example.com",
		"a.myblock.com":        "blocked|your block rule|myblock.com",
		"extra.net":            "blocked|your block rule|extra.net",
		"example.com":          "allowed||",
		"Wild.IO.":             "blocked|block list|wild.io",
		"nope":                 "invalid||",
	}
	for d, want := range cases {
		if got := e.Check(d); got != want {
			t.Errorf("%s: got %q want %q", d, got, want)
		}
	}
}

func TestResolveBlockedAndCached(t *testing.T) {
	e, up := newTestEngine(t, sampleList)
	r := e.Resolve(buildQuery(7, "ads.example.com", typeA))
	if binary.BigEndian.Uint16(r[0:2]) != 7 || r[2]&0x80 == 0 || binary.BigEndian.Uint16(r[6:8]) != 1 {
		t.Fatalf("bad blocked reply % x", r)
	}
	if !equalTail(r, []byte{0, 4, 0, 0, 0, 0}) {
		t.Fatalf("blocked A should be 0.0.0.0: % x", r)
	}
	r6 := e.Resolve(buildQuery(8, "ads.example.com", typeAAAA))
	if len(r6) < 16 || !equalTail(r6, make([]byte, 16)) {
		t.Fatalf("blocked AAAA should be ::")
	}
	rh := e.Resolve(buildQuery(9, "ads.example.com", typeHTTPS))
	if binary.BigEndian.Uint16(rh[6:8]) != 0 {
		t.Fatal("blocked HTTPS should have no answers")
	}
	if up.calls != 0 {
		t.Fatal("blocked queries must not reach upstream")
	}
	for i := uint16(0); i < 3; i++ {
		r = e.Resolve(buildQuery(100+i, "example.com", typeA))
		if binary.BigEndian.Uint16(r[0:2]) != 100+i {
			t.Fatal("ID not restored")
		}
	}
	if up.calls != 1 {
		t.Fatalf("cache: %d upstream calls, want 1", up.calls)
	}
	if e.Total() != 6 || e.Blocked() != 3 {
		t.Fatalf("counters %d/%d", e.Total(), e.Blocked())
	}
	if ttl := minTTL(r); ttl != 300 {
		t.Fatalf("ttl %d", ttl)
	}
}

func equalTail(b, tail []byte) bool {
	if len(b) < len(tail) {
		return false
	}
	return string(b[len(b)-len(tail):]) == string(tail)
}

// ipv4UDP builds a query packet the way Android's resolver sends it through the tunnel.
func ipv4UDP(payload []byte) []byte {
	p := udpPacket{src: []byte{10, 111, 222, 1}, dst: []byte{10, 111, 222, 53}, srcPort: 53, dstPort: 40000}
	pkt := p.reply(payload) // src/dst swapped by reply: comes out 10.111.222.1:40000 -> .53:53
	return pkt
}

func TestPacketRoundTrip(t *testing.T) {
	e, _ := newTestEngine(t, sampleList)
	for _, v6 := range []bool{false, true} {
		var pkt []byte
		if v6 {
			p := udpPacket{v6: true, src: net.ParseIP("fd00:5d5d::53").To16(), dst: net.ParseIP("fd00:5d5d::1").To16(), srcPort: 53, dstPort: 40001}
			pkt = p.reply(buildQuery(42, "doubleclick.net", typeA))
		} else {
			pkt = ipv4UDP(buildQuery(42, "doubleclick.net", typeA))
		}
		in, ok := parseUDP(pkt)
		if !ok || in.dstPort != 53 {
			t.Fatalf("v6=%v: query packet did not parse", v6)
		}
		if !checksumOK(pkt) {
			t.Fatalf("v6=%v: built query has a bad checksum", v6)
		}
		out := e.handlePacket(context.Background(), pkt)
		if out == nil {
			t.Fatalf("v6=%v: no reply", v6)
		}
		rp, ok := parseUDP(out)
		if !ok || rp.srcPort != 53 || rp.dstPort != in.srcPort || string(rp.dst) != string(in.src) {
			t.Fatalf("v6=%v: reply addressing wrong", v6)
		}
		if !checksumOK(out) {
			t.Fatalf("v6=%v: reply checksum wrong", v6)
		}
		if binary.BigEndian.Uint16(rp.payload[0:2]) != 42 {
			t.Fatal("reply id")
		}
	}
	// Not port 53, not UDP, garbage: dropped.
	p := udpPacket{src: []byte{1, 2, 3, 4}, dst: []byte{5, 6, 7, 8}, srcPort: 99, dstPort: 1}
	if e.handlePacket(context.Background(), p.reply([]byte("x"))) != nil {
		t.Fatal("non-DNS packet answered")
	}
	if e.handlePacket(context.Background(), []byte{0x45, 0, 0}) != nil {
		t.Fatal("garbage answered")
	}
}

// checksumOK verifies the IPv4 header and UDP checksums of a packet.
func checksumOK(pkt []byte) bool {
	p, ok := parseUDP(pkt)
	if !ok {
		return false
	}
	var udp []byte
	if p.v6 {
		udp = pkt[40:]
	} else {
		if fold(sum(pkt[:20], 0)) != 0xFFFF {
			return false
		}
		udp = pkt[20:]
	}
	var s uint32
	s = sum(p.src, s)
	s = sum(p.dst, s)
	s += 17 + uint32(len(udp))
	s = sum(udp, s)
	return fold(s) == 0xFFFF
}

func TestDoH(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Content-Type") != "application/dns-message" {
			http.Error(w, "type", 415)
			return
		}
		q, _ := io.ReadAll(r.Body)
		if binary.BigEndian.Uint16(q[0:2]) != 0 {
			http.Error(w, "id must be 0", 400)
			return
		}
		w.Header().Set("Content-Type", "application/dns-message")
		w.Write(answerA(q))
	}))
	defer srv.Close()
	pool := x509.NewCertPool()
	pool.AddCert(srv.Certificate())
	_, port, _ := net.SplitHostPort(srv.Listener.Addr().String())
	// The URL names a host that doesn't resolve: the dialer must use the given IP.
	d := newDoH("https://example.com:"+port+"/dns-query", []string{"127.0.0.1"},
		&tls.Config{RootCAs: pool, ServerName: "example.com"})
	resp, err := d.exchange(context.Background(), buildQuery(0xBEEF, "example.com", typeA))
	if err != nil {
		t.Fatal(err)
	}
	if binary.BigEndian.Uint16(resp[0:2]) != 0xBEEF || minTTL(resp) != 300 {
		t.Fatalf("bad DoH answer % x", resp)
	}
}

func TestUDPUpstream(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Skip(err)
	}
	defer pc.Close()
	go func() {
		buf := make([]byte, 2000)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(answerA(buf[:n]), addr)
		}
	}()
	u, err := parseUpstream("udp " + pc.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	resp, err := u.exchange(context.Background(), buildQuery(5, "example.com", typeA))
	if err != nil || minTTL(resp) != 300 {
		t.Fatalf("udp exchange: %v", err)
	}
}

func TestParseUpstream(t *testing.T) {
	good := []string{"udp 1.1.1.1", "udp 1.1.1.1:5353 [2606:4700::1111]:53", "doh https://dns.quad9.net/dns-query 9.9.9.9"}
	bad := []string{"", "udp", "udp dns.google", "doh https://x/dns-query", "doh http://x/q 1.1.1.1", "tls 1.1.1.1"}
	for _, s := range good {
		if _, err := parseUpstream(s); err != nil {
			t.Errorf("%q: %v", s, err)
		}
	}
	for _, s := range bad {
		if _, err := parseUpstream(s); err == nil {
			t.Errorf("%q should fail", s)
		}
	}
}

func TestBadMessages(t *testing.T) {
	e, _ := newTestEngine(t, "")
	for _, m := range [][]byte{nil, {1, 2}, make([]byte, 12), buildQuery(1, "a.b", 1)[:14]} {
		if e.Resolve(m) != nil {
			t.Errorf("% x answered", m)
		}
	}
	// compression loop
	loop := buildQuery(1, "a.b", 1)[:12]
	loop = append(loop, 0xC0, 12, 0, 1, 0, 1)
	if e.Resolve(loop) != nil {
		t.Error("loop answered")
	}
}
