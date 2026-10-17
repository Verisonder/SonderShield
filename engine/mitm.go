package shield

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	_ "embed"
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// HTTPS filtering. For the apps the user picked (browsers), TLS connections to port 443
// are opened here with a certificate made on the fly and signed by SonderShield's own CA,
// which the user installs. Pages then get the lists' element-hiding CSS and scriptlets.
// Banking, payment and government sites, and any site whose app refuses the certificate,
// pass through untouched.

//go:embed passthrough.txt
var passthroughText string

var builtinPassthrough = func() domainSet {
	s := domainSet{}
	for _, line := range strings.Split(passthroughText, "\n") {
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		if line = strings.ToLower(strings.TrimSpace(line)); line != "" {
			s[line] = 0 // single labels ("gov") are whole top-level domains
		}
	}
	return s
}()

type mitmConfig struct {
	caCert  *x509.Certificate
	caDER   []byte
	caKey   *ecdsa.PrivateKey
	leafKey *ecdsa.PrivateKey
	uids    map[int]bool
	skip    domainSet // the user's own passthrough domains

	mu      sync.Mutex
	leaves  map[string]*tls.Certificate
	learned map[string]time.Time // hosts whose app refused our certificate, until then
}

// GenerateCA makes a new CA for HTTPS filtering: certificate and private key as PEM, in
// one string. The app keeps it and passes it to SetHTTPS.
func GenerateCA() (string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	var id [4]byte
	rand.Read(id[:])
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: fmt.Sprintf("SonderShield %X", id), Organization: []string{"SonderShield"}},
		NotBefore:             time.Now().Add(-24 * time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return "", err
	}
	kb, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return "", err
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})) +
		string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb})), nil
}

// CertOf returns only the certificate part of a GenerateCA string (what the user installs).
func CertOf(ca string) string {
	rest := []byte(ca)
	for {
		var b *pem.Block
		b, rest = pem.Decode(rest)
		if b == nil {
			return ""
		}
		if b.Type == "CERTIFICATE" {
			return string(pem.EncodeToMemory(b))
		}
	}
}

// CertDER returns the certificate as base64 DER, to compare with the phone's CA store.
func CertDER(ca string) string {
	b, _ := pem.Decode([]byte(CertOf(ca)))
	if b == nil {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b.Bytes)
}

// SetHTTPS turns HTTPS filtering on for the given app uids (comma separated) with this
// CA (from GenerateCA). skip: extra domains to never open, one per line. An empty ca or
// uids turns it off. Takes effect for new connections.
func (e *Engine) SetHTTPS(ca string, uids string, skip string) error {
	if strings.TrimSpace(ca) == "" || strings.TrimSpace(uids) == "" {
		e.mitm.Store(nil)
		e.cache.clear()
		return nil
	}
	var cert *x509.Certificate
	var der []byte
	var key *ecdsa.PrivateKey
	rest := []byte(ca)
	for {
		var b *pem.Block
		b, rest = pem.Decode(rest)
		if b == nil {
			break
		}
		switch b.Type {
		case "CERTIFICATE":
			c, err := x509.ParseCertificate(b.Bytes)
			if err != nil {
				return err
			}
			cert, der = c, b.Bytes
		case "EC PRIVATE KEY":
			k, err := x509.ParseECPrivateKey(b.Bytes)
			if err != nil {
				return err
			}
			key = k
		}
	}
	if cert == nil || key == nil {
		return errors.New("CA needs a certificate and a key")
	}
	leaf, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	m := &mitmConfig{caCert: cert, caDER: der, caKey: key, leafKey: leaf, uids: map[int]bool{},
		skip: parseRules(skip), leaves: map[string]*tls.Certificate{}, learned: map[string]time.Time{}}
	if old := e.mitm.Load(); old != nil && bytes.Equal(old.caDER, der) {
		old.mu.Lock()
		for h, t := range old.learned {
			m.learned[h] = t
		}
		old.mu.Unlock()
	}
	for _, u := range strings.Split(uids, ",") {
		if n, err := strconv.Atoi(strings.TrimSpace(u)); err == nil {
			m.uids[n] = true
		}
	}
	e.mitm.Store(m)
	e.cache.clear() // HTTPS records are dropped while filtering is on
	return nil
}

// mitmFor: the config when this app's HTTPS is filtered.
func (e *Engine) mitmFor(uid int) *mitmConfig {
	m := e.mitm.Load()
	if m == nil || uid < 0 || !m.uids[uid] {
		return nil
	}
	return m
}

func (m *mitmConfig) passthrough(host string) bool {
	if _, ok := builtinPassthrough.match(host); ok {
		return true
	}
	if _, ok := m.skip.match(host); ok {
		return true
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if t, ok := m.learned[host]; ok {
		if time.Now().Before(t) {
			return true
		}
		delete(m.learned, host)
	}
	return false
}

func (m *mitmConfig) learn(host string) {
	m.mu.Lock()
	if len(m.learned) > 5000 {
		m.learned = map[string]time.Time{}
	}
	m.learned[host] = time.Now().Add(6 * time.Hour)
	m.mu.Unlock()
}

func (m *mitmConfig) leaf(host string) (*tls.Certificate, error) {
	m.mu.Lock()
	if c, ok := m.leaves[host]; ok && time.Now().Before(c.Leaf.NotAfter.Add(-24*time.Hour)) {
		m.mu.Unlock()
		return c, nil
	}
	m.mu.Unlock()
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	notAfter := time.Now().AddDate(0, 0, 30)
	if notAfter.After(m.caCert.NotAfter) {
		notAfter = m.caCert.NotAfter
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if ip := net.ParseIP(host); ip != nil {
		tmpl.IPAddresses = []net.IP{ip}
	} else {
		tmpl.DNSNames = []string{host}
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, m.caCert, &m.leafKey.PublicKey, m.caKey)
	if err != nil {
		return nil, err
	}
	parsed, _ := x509.ParseCertificate(der)
	c := &tls.Certificate{Certificate: [][]byte{der, m.caDER}, PrivateKey: m.leafKey, Leaf: parsed}
	m.mu.Lock()
	if len(m.leaves) > 1000 {
		m.leaves = map[string]*tls.Certificate{}
	}
	m.leaves[host] = c
	m.mu.Unlock()
	return c, nil
}

// --- ClientHello ---

// readClientHello reads the first TLS handshake message and returns the raw bytes read
// and the server name in it ("" when absent or not TLS).
func readClientHello(r io.Reader) ([]byte, string, error) {
	var raw []byte
	var hs []byte
	need := -1
	for need < 0 || len(hs) < need {
		var hdr [5]byte
		if _, err := io.ReadFull(r, hdr[:]); err != nil {
			return raw, "", err
		}
		raw = append(raw, hdr[:]...)
		if hdr[0] != 22 { // not a handshake record
			return raw, "", nil
		}
		n := int(binary.BigEndian.Uint16(hdr[3:5]))
		body := make([]byte, n)
		if _, err := io.ReadFull(r, body); err != nil {
			return raw, "", err
		}
		raw = append(raw, body...)
		hs = append(hs, body...)
		if need < 0 && len(hs) >= 4 {
			if hs[0] != 1 {
				return raw, "", nil
			}
			need = 4 + int(hs[1])<<16 | int(hs[2])<<8 | int(hs[3])
			if need > 1<<16 {
				return raw, "", nil
			}
		}
		if len(raw) > 1<<17 {
			return raw, "", nil
		}
	}
	return raw, sniFrom(hs[4:need]), nil
}

func sniFrom(h []byte) string {
	// version(2) random(32) session id, cipher suites, compression, extensions
	if len(h) < 34 {
		return ""
	}
	p := h[34:]
	skip := func(lenBytes int) bool {
		if len(p) < lenBytes {
			return false
		}
		n := 0
		for i := 0; i < lenBytes; i++ {
			n = n<<8 | int(p[i])
		}
		if len(p) < lenBytes+n {
			return false
		}
		p = p[lenBytes+n:]
		return true
	}
	if !skip(1) || !skip(2) || !skip(1) || len(p) < 2 {
		return ""
	}
	ext := p[2:]
	if n := int(binary.BigEndian.Uint16(p)); n < len(ext) {
		ext = ext[:n]
	}
	for len(ext) >= 4 {
		typ, n := binary.BigEndian.Uint16(ext), int(binary.BigEndian.Uint16(ext[2:]))
		if len(ext) < 4+n {
			return ""
		}
		data := ext[4 : 4+n]
		ext = ext[4+n:]
		if typ != 0 || len(data) < 5 {
			continue
		}
		list := data[2:]
		for len(list) >= 3 {
			nt, nl := list[0], int(binary.BigEndian.Uint16(list[1:]))
			if len(list) < 3+nl {
				return ""
			}
			if nt == 0 {
				return strings.ToLower(string(list[3 : 3+nl]))
			}
			list = list[3+nl:]
		}
	}
	return ""
}

// prefixConn replays bytes already read before reading on.
type prefixConn struct {
	net.Conn
	r io.Reader
}

func (c *prefixConn) Read(b []byte) (int, error) { return c.r.Read(b) }

func (c *prefixConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

// --- the proxy ---

type dialer interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
}

// httpsProxy serves the filtered connections of one tunnel run.
type httpsProxy struct {
	e    *Engine
	out  dialer
	tr   *http.Transport
	ips  sync.Map    // host -> the address the app was connecting to
	tlsc *tls.Config // upstream TLS (nil: system roots); tests set their own
}

func newHTTPSProxy(e *Engine, out dialer, upstreamTLS *tls.Config) *httpsProxy {
	p := &httpsProxy{e: e, out: out, tlsc: upstreamTLS}
	p.tr = &http.Transport{
		DialContext:           p.dial,
		TLSClientConfig:       upstreamTLS,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
	return p
}

func (p *httpsProxy) close() { p.tr.CloseIdleConnections() }

// dial goes to the address the app itself was connecting to (no second DNS lookup, and
// the same server the app's DNS answer chose).
func (p *httpsProxy) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err == nil {
		if ip, ok := p.ips.Load(host); ok {
			addr = net.JoinHostPort(ip.(string), port)
		}
	}
	return p.out.DialContext(ctx, network, addr)
}

// serve handles one app connection to dst:443. app is closed by the caller.
func (p *httpsProxy) serve(ctx context.Context, m *mitmConfig, app net.Conn, dst string, uid int, counted func()) {
	app.SetReadDeadline(time.Now().Add(10 * time.Second))
	raw, sni, err := readClientHello(app)
	app.SetReadDeadline(time.Time{})
	if err != nil && len(raw) == 0 {
		return
	}
	pc := &prefixConn{Conn: app, r: io.MultiReader(bytes.NewReader(raw), app)}
	if sni == "" || err != nil || m.passthrough(sni) {
		p.relayRaw(ctx, pc, dst, counted)
		return
	}
	p.ips.Store(sni, dst)
	cfg := &tls.Config{
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"http/1.1"},
		GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
			return m.leaf(sni)
		},
	}
	tc := tls.Server(pc, cfg)
	tc.SetDeadline(time.Now().Add(15 * time.Second))
	if err := tc.HandshakeContext(ctx); err != nil {
		// The app doesn't trust our CA here (pinning, or the CA isn't installed): let
		// this site through untouched from now on.
		m.learn(sni)
		return
	}
	tc.SetDeadline(time.Time{})
	counted()
	p.serveHTTP(ctx, tc, sni)
}

func (p *httpsProxy) relayRaw(ctx context.Context, app net.Conn, dst string, counted func()) {
	dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	up, err := p.out.DialContext(dctx, "tcp", net.JoinHostPort(dst, "443"))
	cancel()
	if err != nil {
		return
	}
	defer up.Close()
	counted()
	relay(app, up)
}

var hopHeaders = []string{"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade"}

func removeHop(h http.Header) {
	for _, f := range h["Connection"] {
		for _, k := range strings.Split(f, ",") {
			if k = strings.TrimSpace(k); k != "" {
				h.Del(k)
			}
		}
	}
	for _, k := range hopHeaders {
		h.Del(k)
	}
}

func (p *httpsProxy) serveHTTP(ctx context.Context, c *tls.Conn, sni string) {
	br := bufio.NewReader(c)
	for {
		c.SetReadDeadline(time.Now().Add(2 * time.Minute))
		req, err := http.ReadRequest(br)
		if err != nil {
			return
		}
		c.SetReadDeadline(time.Time{})
		host := req.Host
		if host == "" {
			host = sni
		}
		req.URL.Scheme = "https"
		req.URL.Host = host
		req.RequestURI = ""
		if strings.EqualFold(req.Header.Get("Upgrade"), "websocket") || headerHasToken(req.Header, "Connection", "upgrade") {
			p.upgrade(ctx, c, br, req, host)
			return
		}
		closeAfter := req.Close
		removeHop(req.Header)
		req.Header.Del("Accept-Encoding") // the transport asks for gzip and unpacks it
		req.Close = false
		resp, err := p.tr.RoundTrip(req.WithContext(ctx))
		if err != nil {
			msg := "SonderShield could not reach " + host + ": " + err.Error()
			fmt.Fprintf(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(msg), msg)
			return
		}
		removeHop(resp.Header)
		resp.Proto, resp.ProtoMajor, resp.ProtoMinor = "HTTP/1.1", 1, 1
		if req.Method != http.MethodHead && resp.StatusCode >= 200 && resp.StatusCode < 300 && resp.StatusCode != 204 && isHTML(resp.Header) {
			p.inject(resp, hostOnly(host))
		}
		resp.Uncompressed = false
		resp.TransferEncoding = nil
		if resp.ContentLength < 0 && req.Method != http.MethodHead && resp.StatusCode != 204 && resp.StatusCode != 304 {
			resp.TransferEncoding = []string{"chunked"}
		}
		resp.Close = closeAfter
		resp.Request = req
		err = resp.Write(c)
		resp.Body.Close()
		if err != nil || closeAfter {
			return
		}
	}
}

func hostOnly(h string) string {
	if host, _, err := net.SplitHostPort(h); err == nil {
		return host
	}
	return h
}

func headerHasToken(h http.Header, name, token string) bool {
	for _, v := range h[name] {
		for _, t := range strings.Split(v, ",") {
			if strings.EqualFold(strings.TrimSpace(t), token) {
				return true
			}
		}
	}
	return false
}

func isHTML(h http.Header) bool {
	ct := strings.ToLower(h.Get("Content-Type"))
	return strings.HasPrefix(ct, "text/html") || strings.HasPrefix(ct, "application/xhtml+xml")
}

// upgrade passes a WebSocket (or other Upgrade) request through as a raw byte stream.
func (p *httpsProxy) upgrade(ctx context.Context, app *tls.Conn, br *bufio.Reader, req *http.Request, host string) {
	dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	addr := host
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(addr, "443")
	}
	raw, err := p.dial(dctx, "tcp", addr)
	if err != nil {
		return
	}
	cfg := &tls.Config{ServerName: hostOnly(host), NextProtos: []string{"http/1.1"}}
	if p.tlsc != nil {
		cfg = p.tlsc.Clone()
		cfg.ServerName = hostOnly(host)
		cfg.NextProtos = []string{"http/1.1"}
	}
	up := tls.Client(raw, cfg)
	if err := up.HandshakeContext(dctx); err != nil {
		raw.Close()
		return
	}
	defer up.Close()
	if err := req.Write(up); err != nil {
		return
	}
	relay(&prefixConn{Conn: app, r: br}, up)
}

var (
	headTag  = regexp.MustCompile(`(?i)<head(\s[^>]*)?>`)
	htmlTag  = regexp.MustCompile(`(?i)<html(\s[^>]*)?>`)
	doctype  = regexp.MustCompile(`(?i)<!doctype[^>]*>`)
	nonceSrc = regexp.MustCompile(`'(nonce|sha256|sha384|sha512)-`)
)

// inject puts the site's CSS and scriptlets right after <head>.
func (p *httpsProxy) inject(resp *http.Response, host string) {
	px := p.e.cos.Load().forHost(host)
	if px.css == "" && px.js == "" {
		return
	}
	var nb [12]byte
	rand.Read(nb[:])
	nonce := base64.RawStdEncoding.EncodeToString(nb[:])
	if !fixCSP(resp.Header, nonce, px.js != "") {
		return
	}
	var snip strings.Builder
	if px.css != "" {
		snip.WriteString(`<style nonce="` + nonce + `">` + px.css + `</style>`)
	}
	if px.js != "" {
		snip.WriteString(`<script nonce="` + nonce + `">` + px.js + `</script>`)
	}
	br := bufio.NewReaderSize(resp.Body, 32*1024)
	head, _ := br.Peek(32 * 1024)
	at := 0
	if loc := headTag.FindIndex(head); loc != nil {
		at = loc[1]
	} else if loc := htmlTag.FindIndex(head); loc != nil {
		at = loc[1]
	} else if loc := doctype.FindIndex(head); loc != nil {
		at = loc[1]
	}
	pre := make([]byte, at)
	io.ReadFull(br, pre)
	resp.Body = struct {
		io.Reader
		io.Closer
	}{io.MultiReader(bytes.NewReader(pre), strings.NewReader(snip.String()), br), resp.Body}
	resp.ContentLength = -1
	resp.Header.Del("Content-Length")
	resp.Header.Del("Content-Encoding")
	resp.Header.Del("Etag") // the page is no longer the server's bytes
}

// fixCSP lets our nonce through a Content-Security-Policy. Returns false when the page
// can't take the injection at all (scripts banned outright, say).
func fixCSP(h http.Header, nonce string, script bool) bool {
	vals := h.Values("Content-Security-Policy")
	if len(vals) == 0 {
		return true
	}
	out := make([]string, 0, len(vals))
	for _, v := range vals {
		var policies []string
		for _, pol := range strings.Split(v, ",") {
			dirs := strings.Split(pol, ";")
			names := map[string]int{}
			for i, d := range dirs {
				f := strings.Fields(d)
				if len(f) > 0 {
					names[strings.ToLower(f[0])] = i
				}
			}
			add := func(name string) {
				i, ok := names[name]
				if !ok {
					return
				}
				d := dirs[i]
				low := strings.ToLower(d)
				if strings.Contains(low, "'unsafe-inline'") && !nonceSrc.MatchString(low) {
					return // inline already allowed; a nonce would switch that off
				}
				d = strings.Replace(d, "'none'", "", 1)
				dirs[i] = strings.TrimRight(d, " ") + " 'nonce-" + nonce + "'"
			}
			for _, n := range []string{"style-src", "style-src-elem", "script-src", "script-src-elem"} {
				add(n)
			}
			_, hasStyle := names["style-src"]
			_, hasScript := names["script-src"]
			if !hasStyle || (script && !hasScript) {
				add("default-src")
			}
			policies = append(policies, strings.Join(dirs, ";"))
		}
		out = append(out, strings.Join(policies, ","))
	}
	h.Del("Content-Security-Policy")
	for _, v := range out {
		h.Add("Content-Security-Policy", v)
	}
	return true
}

// relay copies both ways and passes half-closes along.
func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	half := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if c, ok := dst.(interface{ CloseWrite() error }); ok {
			c.CloseWrite()
		}
		done <- struct{}{}
	}
	go half(b, a)
	go half(a, b)
	<-done
	t := time.NewTimer(5 * time.Second)
	select {
	case <-done:
	case <-t.C:
		a.Close()
		b.Close()
	}
	t.Stop()
}
