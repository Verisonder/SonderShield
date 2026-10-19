//go:build windows

package main

import "strings"

// publicV4, publicV6, dohIPs and splitV4V6 live in dnsroutes.go (shared with Linux).

// DNS lockdown ("force all DNS through SonderShield"). Apps and browsers increasingly use
// their own DNS — plain DNS to 8.8.8.8, DNS-over-TLS on port 853, or DNS-over-HTTPS to a
// resolver's IP on 443 — which skips SonderShield entirely. These firewall rules close
// those routes so the only DNS left is the system resolver, which is us.
//
// Only connections to PUBLIC addresses are blocked, so loopback, the LAN and SonderShield's
// own adapter (10.111.222.53 / fd00:5d5d::) keep working. SonderShield's own encrypted
// upstream stays open because the active provider's IPs are left out of the 443 block.

func (p *windowsPlatform) lockName() string { return p.rule + " DNSLock" }

// setDNSLockdown adds or removes the lockdown rules. skipProvider is the upstream id
// whose DoH IPs must stay reachable (SonderShield's own encrypted DNS).
func (p *windowsPlatform) setDNSLockdown(on bool, skipProvider string) error {
	run("netsh", "advfirewall", "firewall", "delete", "rule", "name="+p.lockName())
	if !on {
		return nil
	}
	name := p.lockName()
	block := func(proto, port, ip string) {
		args := []string{"advfirewall", "firewall", "add", "rule", "name=" + name,
			"dir=out", "action=block", "enable=yes", "protocol=" + proto, "remoteip=" + ip}
		if port != "" {
			args = append(args, "remoteport="+port)
		}
		run("netsh", args...)
	}
	// Plain DNS (53) and DNS-over-TLS (853) to any public resolver.
	for _, pub := range []string{publicV4, publicV6} {
		block("UDP", "53", pub)
		block("TCP", "53", pub)
		block("TCP", "853", pub)
	}
	// DNS-over-HTTPS: block the known resolver IPs on 443 (TCP and QUIC/UDP), except the
	// provider we use ourselves.
	var v4, v6 []string
	for id, ips := range dohIPs {
		if id == skipProvider {
			continue
		}
		a, b := splitV4V6(ips)
		v4 = append(v4, a...)
		v6 = append(v6, b...)
	}
	for _, set := range [][]string{v4, v6} {
		if len(set) == 0 {
			continue
		}
		ip := strings.Join(set, ",")
		block("TCP", "443", ip)
		block("UDP", "443", ip)
	}
	return nil
}
