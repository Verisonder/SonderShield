package shield

import (
	"context"
	"net"
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
