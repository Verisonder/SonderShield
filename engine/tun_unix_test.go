//go:build !windows

package shield

import (
	"os"
	"syscall"
	"testing"
	"time"
)

// TestTunnelLoop runs Start on one end of a datagram socket pair, standing in for
// the VPN descriptor.
func TestTunnelLoop(t *testing.T) {
	e, _ := newTestEngine(t, sampleList)
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
	if err != nil {
		t.Skip(err)
	}
	if err := e.Start(fds[0]); err != nil {
		t.Fatal(err)
	}
	peer := os.NewFile(uintptr(fds[1]), "peer")
	defer peer.Close()
	for i, name := range []string{"ads.example.com", "example.com"} {
		if _, err := peer.Write(ipv4UDP(buildQuery(uint16(i), name, typeA))); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 2000)
		peer.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := peer.Read(buf)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		rp, ok := parseUDP(buf[:n])
		if !ok {
			t.Fatal("bad reply packet")
		}
		ip := rp.payload[len(rp.payload)-4:]
		if (name == "example.com") != (ip[0] == 93) {
			t.Fatalf("%s answered %v", name, ip)
		}
	}
	if !e.Running() {
		t.Fatal("not running")
	}
	done := make(chan struct{})
	go func() { e.Stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Stop hung")
	}
	if e.Running() {
		t.Fatal("still running")
	}
}
