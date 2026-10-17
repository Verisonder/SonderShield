package shield

import (
	"encoding/binary"
	"net"
	"sync"
	"time"
)

// ipNames remembers which hostname each IP address was resolved for, so the Windows
// connection monitor can show a domain next to each connection instead of a bare IP.
// It's a small time-bounded map: entries older than the TTL are ignored and dropped.
type ipNames struct {
	mu   sync.Mutex
	m    map[string]ipName
	cap  int
	keep time.Duration
}

type ipName struct {
	name string
	ts   time.Time
}

func newIPNames(cap int, keep time.Duration) *ipNames {
	return &ipNames{m: make(map[string]ipName, cap), cap: cap, keep: keep}
}

// record maps every A/AAAA address in a DNS answer to the name that was asked for.
func (n *ipNames) record(name string, resp []byte, now time.Time) {
	ips := answerIPs(resp)
	if len(ips) == 0 {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.m) > n.cap {
		// Drop everything stale; if still full, start fresh rather than grow without bound.
		for k, v := range n.m {
			if now.Sub(v.ts) > n.keep {
				delete(n.m, k)
			}
		}
		if len(n.m) > n.cap {
			n.m = make(map[string]ipName, n.cap)
		}
	}
	for _, ip := range ips {
		n.m[ip.String()] = ipName{name, now}
	}
}

// lookup returns the hostname last resolved to this IP, or "" if none / too old.
func (n *ipNames) lookup(ip string, now time.Time) string {
	n.mu.Lock()
	defer n.mu.Unlock()
	e, ok := n.m[ip]
	if !ok || now.Sub(e.ts) > n.keep {
		return ""
	}
	return e.name
}

// answerIPs pulls the A and AAAA addresses out of a DNS response.
func answerIPs(resp []byte) []net.IP {
	if len(resp) < headerLen || resp[2]&0x02 != 0 {
		return nil
	}
	qd := int(binary.BigEndian.Uint16(resp[4:6]))
	an := int(binary.BigEndian.Uint16(resp[6:8]))
	off := headerLen
	var err error
	for i := 0; i < qd; i++ {
		if off, err = skipName(resp, off); err != nil {
			return nil
		}
		off += 4
	}
	var out []net.IP
	for i := 0; i < an; i++ {
		if off, err = skipName(resp, off); err != nil || off+10 > len(resp) {
			return out
		}
		rtype := binary.BigEndian.Uint16(resp[off : off+2])
		rdlen := int(binary.BigEndian.Uint16(resp[off+8 : off+10]))
		rd := off + 10
		if rd+rdlen > len(resp) {
			return out
		}
		switch {
		case rtype == typeA && rdlen == 4:
			out = append(out, net.IP(append([]byte(nil), resp[rd:rd+4]...)))
		case rtype == typeAAAA && rdlen == 16:
			out = append(out, net.IP(append([]byte(nil), resp[rd:rd+16]...)))
		}
		off = rd + rdlen
	}
	return out
}
