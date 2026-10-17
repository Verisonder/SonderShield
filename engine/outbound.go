package shield

import (
	"context"
	"net"
	"time"
)

// Outbound makes the connections apps asked for: the real network by default, or a
// WireGuard tunnel.
type outbound interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
	Close()
}

type directOut struct{ d net.Dialer }

func (o *directOut) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return o.d.DialContext(ctx, network, address)
}
func (o *directOut) Close() {}

// OutsideDialer dials like the engine's own sockets: on Windows pinned to the real network
// interface, so it never loops through the tunnel. For the service's list downloads.
func OutsideDialer() *net.Dialer {
	return &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second, Control: dialControl}
}
