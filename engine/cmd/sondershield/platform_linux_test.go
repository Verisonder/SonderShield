//go:build linux

package main

import (
	"strings"
	"testing"
)

// The nftables scripts, captured instead of applied (no root needed).
func TestLinuxRules(t *testing.T) {
	var got []string
	orig := nftRunner
	nftRunner = func(s string) error { got = append(got, s); return nil }
	defer func() { nftRunner = orig }()

	p := &linuxPlatform{}
	p.setSystemDNS("", "")
	p.setDNSLockdown(true, "cf-doh")
	p.setNetworkBlocks([]NetBlock{{Kind: "ip", Value: "1.2.3.0/24"}, {Kind: "ip", Value: "bad"}, {Kind: "port", Value: "6000-6010"}})
	p.setKillSwitch(true)
	if len(got) != 4 {
		t.Fatalf("want 4 scripts, got %d", len(got))
	}
	for i, want := range [][]string{
		{"table inet sondershield_dns", "meta skuid 0 return", "udp dport 53 redirect to :53530"},
		{"table inet sondershield_lock", "meta skuid 0 accept", "1.1.1.1", "th dport 443 drop"},
		{"table inet sondershield_net", "ip daddr 1.2.3.0/24 drop", "tcp dport 6000-6010 drop"},
		{"table inet sondershield_kill", `oifname "lo" accept`},
	} {
		for _, w := range want {
			if !strings.Contains(got[i], w) {
				t.Errorf("script %d is missing %q:\n%s", i, w, got[i])
			}
		}
	}
	if strings.Contains(got[2], "bad") {
		t.Error("an invalid address got into the rules")
	}
}

func TestParseHexAddr(t *testing.T) {
	ip, port := parseHexAddr("0100007F:0035")
	if ip.String() != "127.0.0.1" || port != 53 {
		t.Fatalf("got %v:%d", ip, port)
	}
	ip, port = parseHexAddr("00000000000000000000000001000000:01BB")
	if ip.String() != "::1" || port != 443 {
		t.Fatalf("got %v:%d", ip, port)
	}
}
