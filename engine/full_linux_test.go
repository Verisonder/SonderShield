//go:build linux

package shield

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"syscall"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// phone is a second netstack standing in for the apps: it sends its packets into one end
// of a datagram socket pair, and the engine's full tunnel runs on the other end.
func phone(t *testing.T, fd int) *stack.Stack {
	t.Helper()
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	link, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: 1500})
	if err != nil {
		t.Fatal(err)
	}
	if e := s.CreateNIC(1, link); e != nil {
		t.Fatal(e)
	}
	s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 111, 222, 1}).WithPrefix(),
	}, stack.AddressProperties{})
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s
}

// toLocal sends every connection to the same port on 127.0.0.1, so test servers here stand
// in for the internet (the phone can't address loopback through a tunnel).
type toLocal struct{ d net.Dialer }

func (o *toLocal) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	_, port, _ := net.SplitHostPort(address)
	return o.d.DialContext(ctx, network, net.JoinHostPort("127.0.0.1", port))
}
func (o *toLocal) Close() {}

func TestFullTunnel(t *testing.T) {
	e, _ := newTestEngine(t, sampleList)
	e.testOut = &toLocal{}
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Skip(err)
	}
	if err := e.StartFull(fds[0], 1500); err != nil {
		t.Fatal(err)
	}
	defer e.Stop()
	ph := phone(t, fds[1])
	defer ph.Close()

	// A TCP echo server on this machine; the phone reaches it through the tunnel.
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	port := uint16(ln.Addr().(*net.TCPAddr).Port)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := gonet.DialContextTCP(ctx, ph, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{93, 184, 216, 34}), Port: port}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("tcp through tunnel: %v", err)
	}
	c.Write([]byte("hello through the tunnel"))
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 64)
	n, err := io.ReadAtLeast(c, buf, 24)
	if err != nil || string(buf[:n]) != "hello through the tunnel" {
		t.Fatalf("echo: %q %v", buf[:n], err)
	}
	c.Close()

	// DNS to any server address is answered by the filter: blocked name -> 0.0.0.0.
	u, err := gonet.DialUDP(ph, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{8, 8, 8, 8}), Port: 53}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	u.Write(buildQuery(77, "ads.example.com", typeA))
	u.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err = u.Read(buf)
	if err != nil {
		t.Fatalf("dns: %v", err)
	}
	if binary.BigEndian.Uint16(buf[0:2]) != 77 || string(buf[n-4:n]) != "\x00\x00\x00\x00" {
		t.Fatalf("dns answer % x", buf[:n])
	}

	// UDP to anything else is relayed: an echo server again.
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	go func() {
		b := make([]byte, 2000)
		for {
			n, a, err := pc.ReadFrom(b)
			if err != nil {
				return
			}
			pc.WriteTo(b[:n], a)
		}
	}()
	uport := uint16(pc.LocalAddr().(*net.UDPAddr).Port)
	u2, err := gonet.DialUDP(ph, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{93, 184, 216, 34}), Port: uport}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer u2.Close()
	u2.Write([]byte("udp ping"))
	u2.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err = u2.Read(buf)
	if err != nil || string(buf[:n]) != "udp ping" {
		t.Fatalf("udp echo: %q %v", buf[:n], err)
	}
	if e.Connections() < 1 {
		t.Fatal("connection not counted")
	}
}

func TestParseWireGuard(t *testing.T) {
	conf := `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.0.0.2/32, fd00::2/128
DNS = 1.1.1.1

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
Endpoint = 203.0.113.1:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`
	c, err := parseWireGuard(conf)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.addresses) != 2 || len(c.peers) != 1 || c.peers[0].keepalive != 25 {
		t.Fatalf("parsed %+v", c)
	}
	ipc, err := c.ipc()
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"private_key=c809f3e5", "endpoint=203.0.113.1:51820", "allowed_ip=::/0", "persistent_keepalive_interval=25"} {
		if !contains(ipc, want) {
			t.Errorf("ipc missing %s:\n%s", want, ipc)
		}
	}
	if CheckWireGuard("[Interface]\nAddress = 10.0.0.2/32\n") == "" {
		t.Error("missing key should fail")
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
