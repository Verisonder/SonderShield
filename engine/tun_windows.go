//go:build windows

package shield

import "errors"

// The Windows app (phase 3) serves DNS on a local socket instead of a tunnel fd.
type runner struct{}

func (r *runner) stop() {}

func (e *Engine) Start(fd int) error { return errors.New("not supported on Windows") }
