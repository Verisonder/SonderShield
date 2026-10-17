package shield

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func TestLocalServer(t *testing.T) {
	e, _ := newTestEngine(t, sampleList)
	l, _ := net.ListenPacket("udp", "127.0.0.1:0")
	port := l.LocalAddr().(*net.UDPAddr).Port
	l.Close()
	if err := e.StartLocal(port); err != nil {
		t.Fatal(err)
	}
	defer e.Stop()
	addr := net.JoinHostPort("127.0.0.1", itoa(port))

	u, err := net.Dial("udp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	u.SetDeadline(time.Now().Add(3 * time.Second))
	u.Write(buildQuery(9, "ads.example.com", typeA))
	buf := make([]byte, 512)
	n, err := u.Read(buf)
	if err != nil || binary.BigEndian.Uint16(buf) != 9 || string(buf[n-4:n]) != "\x00\x00\x00\x00" {
		t.Fatalf("udp: % x %v", buf[:n], err)
	}

	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	q := buildQuery(10, "ads.example.com", typeAAAA)
	c.Write(append(binary.BigEndian.AppendUint16(nil, uint16(len(q))), q...))
	var l2 [2]byte
	if _, err := io.ReadFull(c, l2[:]); err != nil {
		t.Fatal(err)
	}
	resp := make([]byte, binary.BigEndian.Uint16(l2[:]))
	io.ReadFull(c, resp)
	if binary.BigEndian.Uint16(resp) != 10 || binary.BigEndian.Uint16(resp[6:8]) != 1 {
		t.Fatalf("tcp: % x", resp)
	}
	e.Stop()
	if e.Running() {
		t.Fatal("still running")
	}
}

func itoa(n int) string {
	b := []byte{}
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}
