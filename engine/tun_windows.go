//go:build windows

package shield

import "errors"

// The Windows app (phase 3) serves DNS on a local socket instead of a tunnel fd.
func (e *Engine) Start(fd int) error { return errors.New("not supported on Windows") }

func (e *Engine) StartFull(fd int, mtu int) error { return errors.New("not supported on Windows") }
