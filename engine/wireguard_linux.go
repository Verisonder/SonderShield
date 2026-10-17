//go:build linux

package shield

import (
	"context"
	"net"
	"net/netip"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// wgOut dials through a user-space WireGuard device: its own little network stack whose
// only way out is the encrypted UDP socket to the peer.
type wgOut struct {
	dev *device.Device
	net *netstack.Net
}

func (o *wgOut) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return o.net.DialContext(ctx, network, address)
}

func (o *wgOut) Close() { o.dev.Close() }

func (e *Engine) makeOutbound() (outbound, error) {
	if e.testOut != nil {
		return e.testOut, nil
	}
	c := e.wg.Load()
	if c == nil {
		return &directOut{}, nil
	}
	cfg, err := c.ipc()
	if err != nil {
		return nil, err
	}
	tunDev, tnet, err := netstack.CreateNetTUN(c.addresses, []netip.Addr{netip.MustParseAddr("1.1.1.1")}, c.mtu)
	if err != nil {
		return nil, err
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	if err := dev.IpcSet(cfg); err != nil {
		dev.Close()
		return nil, err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, err
	}
	return &wgOut{dev: dev, net: tnet}, nil
}
