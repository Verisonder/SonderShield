package shield

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Local DNS server: root mode on Android (iptables sends port 53 here) and, later, the
// Windows app. Serves UDP and TCP on the loopback addresses.

type localRunner struct {
	cancel context.CancelFunc
	pcs    []net.PacketConn
	lns    []net.Listener
	wg     sync.WaitGroup
}

// StartLocal serves DNS on 127.0.0.1 and ::1 at port (UDP and TCP). IPv6 is skipped when
// the phone has no loopback for it.
func (e *Engine) StartLocal(port int) error {
	e.Stop()
	ctx, cancel := context.WithCancel(context.Background())
	r := &localRunner{cancel: cancel}
	p := strconv.Itoa(port)
	var errs []string
	for _, host := range []string{"127.0.0.1", "::1"} {
		addr := net.JoinHostPort(host, p)
		pc, err := net.ListenPacket("udp", addr)
		if err != nil {
			errs = append(errs, err.Error())
			continue
		}
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			pc.Close()
			errs = append(errs, err.Error())
			continue
		}
		r.pcs = append(r.pcs, pc)
		r.lns = append(r.lns, ln)
	}
	if len(r.pcs) == 0 {
		cancel()
		return errors.New(strings.Join(errs, "; "))
	}
	for _, pc := range r.pcs {
		r.wg.Add(1)
		go e.serveLocalUDP(ctx, r, pc)
	}
	for _, ln := range r.lns {
		r.wg.Add(1)
		go e.serveLocalTCP(ctx, r, ln)
	}
	e.runMu.Lock()
	e.run = r
	e.runMu.Unlock()
	return nil
}

func (e *Engine) serveLocalUDP(ctx context.Context, r *localRunner, pc net.PacketConn) {
	defer r.wg.Done()
	sem := make(chan struct{}, 256)
	for {
		buf := make([]byte, 4096)
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
			return
		}
		go func(q []byte) {
			defer func() { <-sem }()
			resp, ok := e.resolve(ctx, q, -1)
			if !ok {
				return
			}
			if len(resp) > 1232 { // keep under common UDP limits; TCP gets the rest
				if qq, err := parseQuestion(q); err == nil {
					resp = truncate(resp, qq)
				}
			}
			pc.WriteTo(resp, from)
		}(buf[:n])
	}
}

func (e *Engine) serveLocalTCP(ctx context.Context, r *localRunner, ln net.Listener) {
	defer r.wg.Done()
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		r.wg.Add(1)
		go func() {
			defer r.wg.Done()
			defer c.Close()
			stop := context.AfterFunc(ctx, func() { c.Close() })
			defer stop()
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
				resp, ok := e.resolve(ctx, q, -1)
				if !ok {
					return
				}
				if _, err := c.Write(append(binary.BigEndian.AppendUint16(nil, uint16(len(resp))), resp...)); err != nil {
					return
				}
			}
		}()
	}
}

func (r *localRunner) stop() {
	r.cancel()
	for _, pc := range r.pcs {
		pc.Close()
	}
	for _, ln := range r.lns {
		ln.Close()
	}
	r.wg.Wait()
}
