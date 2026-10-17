package shield

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"time"
)

type upstream interface {
	exchange(ctx context.Context, query []byte) ([]byte, error)
	String() string
}

// parseUpstream reads the app's upstream setting:
//
//	udp 1.1.1.1 1.0.0.1                       plain DNS, port 53 unless given
//	doh https://dns.quad9.net/dns-query 9.9.9.9 149.112.112.112
//
// DoH needs the server's IPs: the engine's own lookups would otherwise go through
// the tunnel it is serving.
func parseUpstream(spec string) (upstream, error) {
	f := strings.Fields(spec)
	if len(f) < 2 {
		return nil, fmt.Errorf("upstream %q: need a kind and an address", spec)
	}
	switch f[0] {
	case "udp":
		var addrs []string
		for _, a := range f[1:] {
			if _, _, err := net.SplitHostPort(a); err != nil {
				a = net.JoinHostPort(a, "53")
			}
			if host, _, _ := net.SplitHostPort(a); net.ParseIP(host) == nil {
				return nil, fmt.Errorf("upstream %q: %q is not an IP address", spec, a)
			}
			addrs = append(addrs, a)
		}
		return &udpUpstream{servers: addrs}, nil
	case "doh":
		u, err := url.Parse(f[1])
		if err != nil || u.Scheme != "https" || u.Host == "" {
			return nil, fmt.Errorf("upstream %q: bad DoH URL", spec)
		}
		if len(f) < 3 {
			return nil, fmt.Errorf("upstream %q: DoH needs the server's IP addresses", spec)
		}
		var ips []string
		for _, a := range f[2:] {
			if net.ParseIP(a) == nil {
				return nil, fmt.Errorf("upstream %q: %q is not an IP address", spec, a)
			}
			ips = append(ips, a)
		}
		return newDoH(u.String(), ips, nil), nil
	}
	return nil, fmt.Errorf("upstream %q: unknown kind %q", spec, f[0])
}

// udpUpstream: plain DNS, falls back to TCP when the answer is truncated.
type udpUpstream struct {
	servers []string
	next    atomic.Uint32 // the server that answered last goes first
}

func (u *udpUpstream) String() string { return "udp " + strings.Join(u.servers, " ") }

func (u *udpUpstream) exchange(ctx context.Context, query []byte) ([]byte, error) {
	var lastErr error = errors.New("no servers")
	start := int(u.next.Load())
	for i := range u.servers {
		idx := (start + i) % len(u.servers)
		resp, err := udpExchange(ctx, u.servers[idx], query)
		if err == nil && len(resp) > 2 && resp[2]&0x02 != 0 {
			resp, err = tcpExchange(ctx, u.servers[idx], query)
		}
		if err == nil {
			u.next.Store(uint32(idx))
			return resp, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			break
		}
	}
	return nil, lastErr
}

func udpExchange(ctx context.Context, addr string, query []byte) ([]byte, error) {
	d := net.Dialer{Timeout: 2 * time.Second, Control: dialControl}
	c, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	dl := time.Now().Add(2500 * time.Millisecond)
	if t, ok := ctx.Deadline(); ok && t.Before(dl) {
		dl = t
	}
	c.SetDeadline(dl)
	if _, err := c.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	for {
		n, err := c.Read(buf)
		if err != nil {
			return nil, err
		}
		if n >= 2 && n >= headerLen && bytes.Equal(buf[:2], query[:2]) {
			return append([]byte(nil), buf[:n]...), nil
		}
		// Stray or spoofed packet: keep waiting for ours.
	}
}

func tcpExchange(ctx context.Context, addr string, query []byte) ([]byte, error) {
	d := net.Dialer{Timeout: 3 * time.Second, Control: dialControl}
	c, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(4 * time.Second))
	msg := binary.BigEndian.AppendUint16(nil, uint16(len(query)))
	msg = append(msg, query...)
	if _, err := c.Write(msg); err != nil {
		return nil, err
	}
	var l [2]byte
	if _, err := io.ReadFull(c, l[:]); err != nil {
		return nil, err
	}
	resp := make([]byte, binary.BigEndian.Uint16(l[:]))
	if _, err := io.ReadFull(c, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

// dohUpstream: DNS over HTTPS (RFC 8484, POST), dialled straight to fixed IPs.
type dohUpstream struct {
	url    string
	ips    []string
	client *http.Client
}

func newDoH(u string, ips []string, tlsConf *tls.Config) *dohUpstream {
	d := &dohUpstream{url: u, ips: ips}
	var next atomic.Uint32
	dialer := &net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second, Control: dialControl}
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			_, port, err := net.SplitHostPort(addr)
			if err != nil {
				port = "443"
			}
			var lastErr error
			start := int(next.Load())
			for i := range ips {
				idx := (start + i) % len(ips)
				c, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(ips[idx], port))
				if err == nil {
					next.Store(uint32(idx))
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
		TLSClientConfig:     tlsConf,
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        8,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 4 * time.Second,
		Proxy:               nil,
	}
	d.client = &http.Client{Transport: tr, Timeout: 5 * time.Second}
	return d
}

func (d *dohUpstream) String() string { return "doh " + d.url + " " + strings.Join(d.ips, " ") }

func (d *dohUpstream) exchange(ctx context.Context, query []byte) ([]byte, error) {
	id := binary.BigEndian.Uint16(query[0:2])
	q := append([]byte(nil), query...)
	setID(q, 0) // RFC 8484: ID 0 so HTTP caches can share answers
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.url, bytes.NewReader(q))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := d.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("doh: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, err
	}
	if len(body) < headerLen {
		return nil, errBadMessage
	}
	setID(body, id)
	return body, nil
}
