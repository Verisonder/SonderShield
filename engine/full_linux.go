//go:build linux

package shield

import (
	"syscall"

	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// StartFull serves the whole tunnel (all routes) on fd, with the given MTU. Connections
// go out directly, or through WireGuard when SetWireGuard was given a config.
func (e *Engine) StartFull(fd int, mtu int) error {
	e.Stop()
	return e.startStack(mtu, func() (stack.LinkEndpoint, error) {
		return fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: uint32(mtu)})
	}, func() { syscall.Close(fd) }, nil)
}
