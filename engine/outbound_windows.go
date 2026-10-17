//go:build windows

package shield

import (
	"encoding/binary"
	"sync/atomic"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/conn"
)

// Windows has no per-app VPN exclusion: the engine's own sockets are pinned to the real
// network interface instead, so they don't loop back into the tunnel.

const (
	ipUnicastIf   = 31 // IP_UNICAST_IF
	ipv6UnicastIf = 31 // IPV6_UNICAST_IF
)

var outIf4, outIf6 atomic.Int32

// SetOutsideInterface tells the engine which interface reaches the internet (its index for
// IPv4 and IPv6, 0 = let Windows pick). The service updates it when the network changes.
func (e *Engine) SetOutsideInterface(index4, index6 int) {
	outIf4.Store(int32(index4))
	outIf6.Store(int32(index6))
}

func (e *Engine) directOut() outbound {
	o := &directOut{}
	o.d.Control = dialControl
	return o
}

// dialControl pins a socket to the outside interface (every socket the engine opens:
// upstream DNS, relayed connections, list downloads).
func dialControl(network, address string, c syscall.RawConn) error {
	var serr error
	err := c.Control(func(fd uintptr) {
		h := windows.Handle(fd)
		if network == "tcp6" || network == "udp6" {
			if i := outIf6.Load(); i > 0 {
				serr = windows.SetsockoptInt(h, windows.IPPROTO_IPV6, ipv6UnicastIf, int(i))
			}
			return
		}
		if i := outIf4.Load(); i > 0 {
			// IPv4 wants the index in network byte order.
			var b [4]byte
			binary.BigEndian.PutUint32(b[:], uint32(i))
			serr = windows.SetsockoptInt(h, windows.IPPROTO_IP, ipUnicastIf, int(*(*uint32)(unsafe.Pointer(&b[0]))))
		}
	})
	if err != nil {
		return err
	}
	return serr
}

// bindOutside pins WireGuard's own UDP socket to the real interface too.
func (e *Engine) bindOutside(b conn.Bind) {
	if s, ok := b.(conn.BindSocketToInterface); ok {
		if i := outIf4.Load(); i > 0 {
			s.BindSocketToInterface4(uint32(i), false)
		}
		if i := outIf6.Load(); i > 0 {
			s.BindSocketToInterface6(uint32(i), false)
		}
	}
}
