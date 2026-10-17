//go:build windows

package shield

import "errors"

// Windows has no tunnel file descriptor: DNS-only mode is StartLocal, full mode StartTun.
func (e *Engine) Start(fd int) error { return errors.New("not supported on Windows") }
