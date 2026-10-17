package shield

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const cosmeticList = `! test list
||ads.example.com^
##.ad-banner
##div[id^="google_ads"]
example.test##.local-ad
~example.test##.not-here
example.test#@#.ad-banner
example.test##+js(set, adsEnabled, false)
example.test##+js(nosuchscriptlet)
other.test#%#//scriptlet('abort-on-property-read', 'popup')
example.*##.entity-ad
##.x:has-text(Ad)
@@||clean.test^$generichide
`

func loadCosmetics(t *testing.T, e *Engine, list string) {
	t.Helper()
	cos := newCosmetics()
	if _, err := parseList(strings.NewReader(list), domainSet{}, domainSet{}, 1, cos); err != nil {
		t.Fatal(err)
	}
	cos.finish()
	e.cos.Store(cos)
}

func TestCosmetics(t *testing.T) {
	e := NewEngine()
	loadCosmetics(t, e, cosmeticList)
	c := e.cos.Load()
	px := c.forHost("www.example.test")
	for _, want := range []string{".local-ad", `div[id^="google_ads"]`, ".entity-ad"} {
		if !strings.Contains(px.css, want) {
			t.Errorf("css for example.test lacks %s:\n%s", want, px.css)
		}
	}
	for _, not := range []string{".ad-banner", ".not-here", "has-text"} {
		if strings.Contains(px.css, not) {
			t.Errorf("css for example.test has %s", not)
		}
	}
	if !strings.Contains(px.js, `S["set-constant"]("adsEnabled","false")`) || strings.Contains(px.js, "nosuchscriptlet") {
		t.Errorf("js: %s", px.js)
	}
	o := c.forHost("other.test")
	if !strings.Contains(o.css, ".ad-banner") || !strings.Contains(o.css, ".not-here") || !strings.Contains(o.js, "abort-on-property-read") {
		t.Errorf("other.test css %q, js has aopr %v", o.css, strings.Contains(o.js, "abort-on-property-read"))
	}
	if cl := c.forHost("clean.test"); strings.Contains(cl.css, ".ad-banner") {
		t.Error("generichide ignored")
	}
}

func TestClientHelloSNI(t *testing.T) {
	cl, sv := net.Pipe()
	go tls.Client(cl, &tls.Config{ServerName: "Www.Example.Test", InsecureSkipVerify: true}).Handshake()
	raw, sni, err := readClientHello(sv)
	if err != nil || sni != "www.example.test" || len(raw) < 100 {
		t.Fatalf("sni %q err %v len %d", sni, err, len(raw))
	}
	cl.Close()
	sv.Close()
}

type fixedDial struct{ addr string }

func (f fixedDial) DialContext(ctx context.Context, network, _ string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, f.addr)
}

func TestHTTPSFiltering(t *testing.T) {
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/json" {
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"a":1}`)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self' https://cdn.test")
		io.WriteString(w, "<!doctype html><html><head><title>t</title></head><body>"+strings.Repeat("x", 100000)+"</body></html>")
	}))
	defer up.Close()
	upRoots := x509.NewCertPool()
	upRoots.AddCert(up.Certificate())

	e := NewEngine()
	loadCosmetics(t, e, cosmeticList)
	ca, err := GenerateCA()
	if err != nil {
		t.Fatal(err)
	}
	if err := e.SetHTTPS(ca, "10123", "skipped.test"); err != nil {
		t.Fatal(err)
	}
	m := e.mitmFor(10123)
	if m == nil || e.mitmFor(10124) != nil {
		t.Fatal("uid selection")
	}
	p := newHTTPSProxy(e, fixedDial{up.Listener.Addr().String()}, &tls.Config{RootCAs: upRoots, ServerName: "example.com"})
	defer p.close()

	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); p.serve(context.Background(), m, c, "127.0.0.1", 10123, func() {}) }()
		}
	}()
	ours := x509.NewCertPool()
	ours.AppendCertsFromPEM([]byte(CertOf(ca)))

	// httptest's certificate is for example.com: ask for that name through the proxy.
	c, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{RootCAs: ours, ServerName: "www.example.test", NextProtos: []string{"h2", "http/1.1"}})
	if err != nil {
		t.Fatalf("handshake with our CA: %v", err)
	}
	if c.ConnectionState().NegotiatedProtocol != "http/1.1" {
		t.Errorf("alpn %q", c.ConnectionState().NegotiatedProtocol)
	}
	br := bufio.NewReader(c)
	get := func(path string) *http.Response {
		req, _ := http.NewRequest("GET", "https://www.example.test"+path, nil)
		req.Header.Set("Accept-Encoding", "gzip, br")
		if err := req.Write(c); err != nil {
			t.Fatal(err)
		}
		resp, err := http.ReadResponse(br, req)
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}
	resp := get("/")
	body, _ := io.ReadAll(resp.Body)
	s := string(body)
	if resp.StatusCode != 200 || !strings.HasPrefix(s, "<!doctype html><html><head><style nonce=") || !strings.Contains(s, ".local-ad") || !strings.Contains(s, "set-constant") || !strings.HasSuffix(s, "</body></html>") {
		t.Fatalf("page %d: %.300s", resp.StatusCode, s)
	}
	csp := resp.Header.Get("Content-Security-Policy")
	if !strings.Contains(csp, "script-src 'self' https://cdn.test 'nonce-") || !strings.Contains(csp, "default-src 'self' 'nonce-") {
		t.Errorf("csp %q", csp)
	}
	// Same connection, a second request: keep-alive works and JSON is untouched.
	resp = get("/json")
	body, _ = io.ReadAll(resp.Body)
	if string(body) != `{"a":1}` {
		t.Errorf("json %q", body)
	}
	c.Close()

	// A pinned/passthrough site gets the server's own certificate.
	for _, name := range []string{"www.paypal.com", "skipped.test"} {
		c2, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{ServerName: name, InsecureSkipVerify: true})
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if iss := c2.ConnectionState().PeerCertificates[0].Issuer.Organization; len(iss) > 0 && iss[0] == "SonderShield" {
			t.Errorf("%s was opened", name)
		}
		c2.Close()
	}

	// An app that refuses our certificate: the site passes through from then on.
	_, err = tls.Dial("tcp", ln.Addr().String(), &tls.Config{ServerName: "pinned.test"})
	if err == nil {
		t.Fatal("untrusted CA accepted")
	}
	time.Sleep(50 * time.Millisecond)
	if !m.passthrough("pinned.test") {
		t.Error("refusal not learned")
	}
}

func TestFixCSP(t *testing.T) {
	h := http.Header{}
	h.Set("Content-Security-Policy", "script-src 'self' 'unsafe-inline'; style-src 'none'")
	fixCSP(h, "N", true)
	got := h.Get("Content-Security-Policy")
	if strings.Contains(got, "script-src 'self' 'unsafe-inline' 'nonce") || !strings.Contains(got, "style-src 'nonce-N'") {
		t.Errorf("%q", got)
	}
}

func TestHTTPSRecordsDropped(t *testing.T) {
	e := NewEngine()
	ca, _ := GenerateCA()
	e.SetHTTPS(ca, "1", "")
	resp := e.Resolve(buildQuery(5, "www.example.test", typeHTTPS))
	if len(resp) < 12 || resp[7] != 0 || resp[3]&0x0f != 0 {
		t.Fatalf("% x", resp)
	}
}
