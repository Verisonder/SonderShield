//go:build linux

package shield

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// Full-tunnel mode: every packet from the apps comes into a user-space TCP/IP stack
// (gVisor netstack). Each TCP connection and UDP flow is re-made from this process, which
// the app excludes from its own VPN, so it goes out on the real network (or through
// WireGuard). DNS, on any server address, is answered by the filter.

type fullRunner struct {
	proxy  *httpsProxy
	s      *stack.Stack
	cancel context.CancelFunc
	out    outbound
	fd     int
	wg     sync.WaitGroup
}

// StartFull serves the whole tunnel (all routes) on fd, with the given MTU. Connections
// go out directly, or through WireGuard when SetWireGuard was given a config.
func (e *Engine) StartFull(fd int, mtu int) error {
	e.Stop()
	out, err := e.makeOutbound()
	if err != nil {
		syscall.Close(fd)
		return err
	}
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
		HandleLocal:        false,
	})
	// Handlers and options first: the NIC starts reading packets as soon as it exists.
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	ctx, cancel := context.WithCancel(context.Background())
	r := &fullRunner{s: s, cancel: cancel, out: out, fd: fd}
	r.proxy = newHTTPSProxy(e, out, e.testTLS)
	tcpFwd := tcp.NewForwarder(s, 0, 4096, func(req *tcp.ForwarderRequest) { e.onTCP(ctx, r, req) })
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, func(req *udp.ForwarderRequest) { e.onUDP(ctx, r, req) })
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	link, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: uint32(mtu)})
	if err != nil {
		cancel()
		s.Close()
		out.Close()
		syscall.Close(fd)
		return err
	}
	const nic = 1
	if terr := s.CreateNIC(nic, link); terr != nil {
		cancel()
		s.Close()
		out.Close()
		return errors.New(terr.String())
	}
	// Accept packets for any address (the apps' destinations) and send from any address.
	s.SetPromiscuousMode(nic, true)
	s.SetSpoofing(nic, true)
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nic},
		{Destination: header.IPv6EmptySubnet, NIC: nic},
	})

	e.runMu.Lock()
	e.run = r
	e.runMu.Unlock()
	return nil
}

func (r *fullRunner) stop() {
	r.cancel()
	r.proxy.close()
	r.s.Close()
	r.out.Close()
	r.wg.Wait()
	syscall.Close(r.fd)
}

// endpoint id: Local* is where the app was connecting to, Remote* is the app's side.
func flowAddrs(id stack.TransportEndpointID) (src net.IP, srcPort int, dst net.IP, dstPort int) {
	return net.IP(id.RemoteAddress.AsSlice()), int(id.RemotePort), net.IP(id.LocalAddress.AsSlice()), int(id.LocalPort)
}

func (e *Engine) onTCP(ctx context.Context, r *fullRunner, req *tcp.ForwarderRequest) {
	id := req.ID()
	src, srcPort, dst, dstPort := flowAddrs(id)
	uid := e.appFor(6, src, srcPort, dst, dstPort)
	if e.firewalled(uid) {
		req.Complete(true) // reset: this app has no internet
		return
	}
	var wq waiter.Queue
	ep, terr := req.CreateEndpoint(&wq)
	if terr != nil {
		req.Complete(true)
		return
	}
	req.Complete(false)
	app := gonet.NewTCPConn(&wq, ep)

	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		defer app.Close()
		if dstPort == 53 {
			e.serveDNSOverTCP(ctx, app, uid)
			return
		}
		if m := e.mitmFor(uid); m != nil && dstPort == 443 {
			r.proxy.serve(ctx, m, app, dst.String(), uid, func() { e.conns.Add(1) })
			return
		}
		dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		up, err := r.out.DialContext(dctx, "tcp", net.JoinHostPort(dst.String(), strconv.Itoa(dstPort)))
		cancel()
		if err != nil {
			return
		}
		defer up.Close()
		e.conns.Add(1)
		relay(app, up)
	}()
}

func (e *Engine) serveDNSOverTCP(ctx context.Context, c *gonet.TCPConn, uid int) {
	for {
		c.SetReadDeadline(time.Now().Add(30 * time.Second))
		var l [2]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return
		}
		q := make([]byte, binary.BigEndian.Uint16(l[:]))
		if _, err := io.ReadFull(c, q); err != nil {
			return
		}
		resp, ok := e.resolve(ctx, q, uid)
		if !ok {
			return
		}
		out := binary.BigEndian.AppendUint16(nil, uint16(len(resp)))
		if _, err := c.Write(append(out, resp...)); err != nil {
			return
		}
	}
}

func (e *Engine) onUDP(ctx context.Context, r *fullRunner, req *udp.ForwarderRequest) {
	id := req.ID()
	src, srcPort, dst, dstPort := flowAddrs(id)
	uid := e.appFor(17, src, srcPort, dst, dstPort)
	if e.firewalled(uid) {
		return
	}
	if dstPort == 443 && e.mitmFor(uid) != nil {
		return // no QUIC for filtered apps: they fall back to TCP, which can be filtered
	}
	var wq waiter.Queue
	ep, terr := req.CreateEndpoint(&wq)
	if terr != nil {
		return
	}
	app := gonet.NewUDPConn(&wq, ep)

	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		defer app.Close()
		if dstPort == 53 {
			e.serveDNSOverUDP(ctx, app, uid)
			return
		}
		dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		up, err := r.out.DialContext(dctx, "udp", net.JoinHostPort(dst.String(), strconv.Itoa(dstPort)))
		cancel()
		if err != nil {
			return
		}
		defer up.Close()
		relayUDP(ctx, app, up)
	}()
}

func (e *Engine) serveDNSOverUDP(ctx context.Context, c *gonet.UDPConn, uid int) {
	buf := make([]byte, 4096)
	for {
		c.SetReadDeadline(time.Now().Add(20 * time.Second))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		q := append([]byte(nil), buf[:n]...)
		go func() {
			if resp, ok := e.resolve(ctx, q, uid); ok {
				c.Write(resp)
			}
		}()
	}
}

// relayUDP passes datagrams both ways until neither side sends for a minute (two minutes
// for QUIC-sized flows would hold too many sockets; apps re-open when needed).
func relayUDP(ctx context.Context, app *gonet.UDPConn, up net.Conn) {
	idle := func() time.Time { return time.Now().Add(60 * time.Second) }
	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, 65535)
		for {
			app.SetReadDeadline(idle())
			n, err := app.Read(buf)
			if err != nil {
				break
			}
			if _, err := up.Write(buf[:n]); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	go func() {
		buf := make([]byte, 65535)
		for {
			up.SetReadDeadline(idle())
			n, err := up.Read(buf)
			if err != nil {
				break
			}
			if _, err := app.Write(buf[:n]); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
}
