//go:build windows

package shield

import (
	"context"
	"os"

	"golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// StartTun creates a WinTun adapter called name (wintun.dll next to the program) and serves
// the whole tunnel on it. The caller gives the adapter its address, DNS and routes.
func (e *Engine) StartTun(name string, mtu int) error {
	e.Stop()
	dev, err := tun.CreateTUN(name, mtu)
	if err != nil {
		return err
	}
	ep := channel.New(1024, uint32(mtu), "")
	return e.startStack(mtu, func() (stack.LinkEndpoint, error) { return ep, nil }, func() {
		ep.Close()
		dev.Close()
	}, func(ctx context.Context) { pumpTun(ctx, dev, ep) })
}

// pumpTun moves packets between the adapter and the stack until ctx ends.
func pumpTun(ctx context.Context, dev tun.Device, ep *channel.Endpoint) {
	go func() { // stack -> adapter
		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			view := pkt.ToView()
			pkt.DecRef()
			b := view.AsSlice()
			if _, err := dev.Write([][]byte{b}, 0); err != nil && ctx.Err() != nil {
				return
			}
			view.Release()
		}
	}()
	n := dev.BatchSize()
	bufs := make([][]byte, n)
	for i := range bufs {
		bufs[i] = make([]byte, 65535)
	}
	sizes := make([]int, n)
	for ctx.Err() == nil { // adapter -> stack
		count, err := dev.Read(bufs, sizes, 0)
		if err != nil {
			if ctx.Err() != nil || err == os.ErrClosed {
				return
			}
			continue
		}
		for i := 0; i < count; i++ {
			p := bufs[i][:sizes[i]]
			if len(p) == 0 {
				continue
			}
			pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), p...))})
			switch p[0] >> 4 {
			case 4:
				ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
			case 6:
				ep.InjectInbound(header.IPv6ProtocolNumber, pkb)
			}
			pkb.DecRef()
		}
	}
}
