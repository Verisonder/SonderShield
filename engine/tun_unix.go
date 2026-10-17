//go:build !windows

package shield

import (
	"context"
	"errors"
	"os"
	"sync"
	"syscall"
)

type dnsRunner struct {
	f      *os.File
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

// Start serves DNS on a tunnel file descriptor (Android: VpnService's, detached). The
// engine owns the descriptor from here and closes it in Stop.
func (e *Engine) Start(fd int) error {
	e.Stop()
	// Non-blocking so the descriptor joins Go's poller and Close unblocks the reader.
	if err := syscall.SetNonblock(fd, true); err != nil {
		syscall.Close(fd)
		return err
	}
	f := os.NewFile(uintptr(fd), "tun")
	if f == nil {
		return errors.New("bad tunnel descriptor")
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &dnsRunner{f: f, cancel: cancel}
	e.runMu.Lock()
	e.run = r
	e.runMu.Unlock()
	r.wg.Add(1)
	go e.loop(ctx, r)
	return nil
}

func (e *Engine) loop(ctx context.Context, r *dnsRunner) {
	defer r.wg.Done()
	sem := make(chan struct{}, 256)
	for {
		buf := make([]byte, 1600)
		n, err := r.f.Read(buf)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, os.ErrClosed) {
				return
			}
			// EAGAIN can't reach here through the poller; anything else ends the loop.
			return
		}
		pkt := buf[:n]
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
			return
		}
		go func() {
			defer func() { <-sem }()
			if out := e.handlePacket(ctx, pkt); out != nil && ctx.Err() == nil {
				r.f.Write(out)
			}
		}()
	}
}

func (r *dnsRunner) stop() {
	r.cancel()
	r.f.Close()
	r.wg.Wait()
}
