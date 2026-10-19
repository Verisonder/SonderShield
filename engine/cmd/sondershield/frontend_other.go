//go:build !windows && !linux

package main

// Outside Windows (local testing) the interface opens in the browser and the program runs
// until Quit is pressed.
func runFrontend(a *App, srv *server, hidden bool) {
	quit := make(chan struct{})
	srv.onQuit = func() { a.stop(); close(quit) }
	if !hidden {
		openWindow(srv.url())
	}
	<-quit
}
