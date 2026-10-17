//go:build windows

package main

import "strings"

// DNS lockdown ("force all DNS through SonderShield"). Apps and browsers increasingly use
// their own DNS — plain DNS to 8.8.8.8, DNS-over-TLS on port 853, or DNS-over-HTTPS to a
// resolver's IP on 443 — which skips SonderShield entirely. These firewall rules close
// those routes so the only DNS left is the system resolver, which is us.
//
// Only connections to PUBLIC addresses are blocked, so loopback, the LAN and SonderShield's
// own adapter (10.111.222.53 / fd00:5d5d::) keep working. SonderShield's own encrypted
// upstream stays open because the active provider's IPs are left out of the 443 block.

// publicV4 is every IPv4 address that is NOT private (RFC1918), as the ranges either side
// of 10/8, 172.16/12 and 192.168/16. Used so DNS blocks never touch the local network.
const publicV4 = "0.0.0.0/5,8.0.0.0/7,11.0.0.0/8,12.0.0.0/6,16.0.0.0/4,32.0.0.0/3," +
	"64.0.0.0/2,128.0.0.0/3,160.0.0.0/5,168.0.0.0/6,172.0.0.0/12,172.32.0.0/11," +
	"172.64.0.0/10,172.128.0.0/9,173.0.0.0/8,174.0.0.0/7,176.0.0.0/4,192.0.0.0/9," +
	"192.128.0.0/11,192.160.0.0/13,192.169.0.0/16,192.170.0.0/15,192.172.0.0/14," +
	"192.176.0.0/12,192.192.0.0/10,193.0.0.0/8,194.0.0.0/7,196.0.0.0/6,200.0.0.0/5," +
	"208.0.0.0/4,224.0.0.0/3"

// publicV6 is global-unicast IPv6 (2000::/3), which excludes unique-local (our adapter)
// and link-local.
const publicV6 = "2000::/3"

// dohIPs are the well-known public DNS-over-HTTPS resolver addresses, grouped by provider.
// Blocking them on 443 stops a browser's "Secure DNS" from reaching them. The provider
// SonderShield itself uses upstream is skipped, so our own encrypted DNS keeps working.
var dohIPs = map[string][]string{
	"cf-doh":     {"1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3", "2606:4700:4700::1111", "2606:4700:4700::1001", "2606:4700:4700::1112", "2606:4700:4700::1002"},
	"google-doh": {"8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"},
	"quad9-doh":  {"9.9.9.9", "149.112.112.112", "9.9.9.11", "149.112.112.11", "2620:fe::fe", "2620:fe::9"},
	"opendns":    {"208.67.222.222", "208.67.220.220", "2620:119:35::35", "2620:119:53::53"},
	"adguard":    {"94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"},
	"nextdns":    {"45.90.28.0/24", "45.90.30.0/24", "2a07:a8c0::/32", "2a07:a8c1::/32"},
	"mullvad":    {"194.242.2.2", "194.242.2.3", "2a07:e340::2", "2a07:e340::3"},
	"clean":      {"185.228.168.9", "185.228.169.9", "2a0d:2a00:1::2", "2a0d:2a00:2::2"},
	"controld":   {"76.76.2.0", "76.76.10.0", "2606:1a40::", "2606:1a40:1::"},
}

func splitV4V6(addrs []string) (v4, v6 []string) {
	for _, a := range addrs {
		if strings.Contains(a, ":") {
			v6 = append(v6, a)
		} else {
			v4 = append(v4, a)
		}
	}
	return
}

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
