package main

import (
	"net"
	"strconv"
	"strings"
)

// validIPOrCIDR reports whether s is a single IP or a CIDR range.
func validIPOrCIDR(s string) bool {
	s = strings.TrimSpace(s)
	if net.ParseIP(s) != nil {
		return true
	}
	_, _, err := net.ParseCIDR(s)
	return err == nil
}

// validPort reports whether s is a port (1-65535) or a range "low-high".
func validPort(s string) bool {
	s = strings.TrimSpace(s)
	lo, hi, found := strings.Cut(s, "-")
	a, err := strconv.Atoi(strings.TrimSpace(lo))
	if err != nil || a < 1 || a > 65535 {
		return false
	}
	if !found {
		return true
	}
	b, err := strconv.Atoi(strings.TrimSpace(hi))
	return err == nil && b >= a && b <= 65535
}
