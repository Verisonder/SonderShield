//go:build windows

package main

import (
	"net"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

// currentDNS reads, from Windows itself, which DNS servers each live adapter is using.
// This is the ground truth the diagnostic compares against what SonderShield set.
func (p *windowsPlatform) currentDNS() []AdapterDNS {
	var size uint32 = 16 << 10
	var buf []byte
	for tries := 0; tries < 3; tries++ {
		buf = make([]byte, size)
		aa := (*windows.IpAdapterAddresses)(unsafe.Pointer(&buf[0]))
		err := windows.GetAdaptersAddresses(windows.AF_UNSPEC, windows.GAA_FLAG_INCLUDE_GATEWAYS, 0, aa, &size)
		if err == windows.ERROR_BUFFER_OVERFLOW {
			continue
		}
		if err != nil {
			return nil
		}
		break
	}
	aa := (*windows.IpAdapterAddresses)(unsafe.Pointer(&buf[0]))
	var out []AdapterDNS
	for a := aa; a != nil; a = a.Next {
		if a.OperStatus != windows.IfOperStatusUp || a.IfType == windows.IF_TYPE_SOFTWARE_LOOPBACK {
			continue
		}
		name := windows.UTF16PtrToString(a.FriendlyName)
		ours := strings.EqualFold(name, tunName)
		// Only adapters that reach the internet (have a gateway), plus our own.
		if a.FirstGatewayAddress == nil && !ours {
			continue
		}
		var servers []string
		for d := a.FirstDnsServerAddress; d != nil; d = d.Next {
			if ip := sockaddrIP(&d.Address); ip != "" {
				servers = append(servers, ip)
			}
		}
		out = append(out, AdapterDNS{Name: name, Servers: servers, Ours: ours})
	}
	return out
}

// sockaddrIP pulls the IP string out of a Windows SOCKET_ADDRESS.
func sockaddrIP(sa *windows.SocketAddress) string {
	raw := sa.Sockaddr
	if raw == nil {
		return ""
	}
	switch raw.Addr.Family {
	case windows.AF_INET:
		p := (*windows.RawSockaddrInet4)(unsafe.Pointer(raw))
		return net.IP(p.Addr[:]).String()
	case windows.AF_INET6:
		p := (*windows.RawSockaddrInet6)(unsafe.Pointer(raw))
		return net.IP(p.Addr[:]).String()
	}
	return ""
}
