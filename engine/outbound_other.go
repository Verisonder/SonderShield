//go:build !windows

package shield

import (
	"syscall"

	"golang.zx2c4.com/wireguard/conn"
)

// On Android the app is excluded from its own VPN, so plain sockets already go outside.
func (e *Engine) directOut() outbound { return &directOut{} }

func (e *Engine) bindOutside(conn.Bind) {}

// dialControl: nothing to do outside Windows.
var dialControl func(network, address string, c syscall.RawConn) error
