// Command sondershield is the Windows app: a local DNS ad/tracker/malware blocker with a
// per-program firewall, in the same style as the phone app. It runs the shared engine,
// points the computer's DNS at it, and shows a window served from the embedded UI.
package main

import (
	"net/http"
	"os"
	"time"
)

func main() {
	// If another copy is already running, just open its window and exit.
	if url, ok := existingInstance(); ok {
		openWindow(url)
		return
	}
	// Blocking DNS on port 53 and changing network settings need administrator rights.
	if !ensureAdmin() {
		return // we relaunched elevated, or the user declined
	}

	cleanupOldUpdate()
	plat := newPlatform()
	a := newApp(plat)
	srv, err := newServer(a)
	if err != nil {
		fatal("Could not start the local interface: " + err.Error())
		return
	}
	writeInstance(plat.dataDir(), srv.url())
	defer removeInstance(plat.dataDir())

	go srv.serve()

	// Resume if protection was on when we last quit.
	if a.config().On {
		a.start()
	}

	// Register the quit endpoint now that we have the server and app.
	quit := make(chan struct{})
	http.DefaultServeMux.HandleFunc("/__never", func(http.ResponseWriter, *http.Request) {})
	srv.onQuit = func() {
		a.stop()
		close(quit)
	}

	// Started by "start with Windows": don't pop the window up, just run.
	if !hasFlag("--tray") {
		openWindow(srv.url())
	}

	<-quit
	time.Sleep(200 * time.Millisecond)
}

func hasFlag(f string) bool {
	for _, a := range os.Args[1:] {
		if a == f {
			return true
		}
	}
	return false
}

// existingInstance returns the running copy's window URL, if one answers.
func existingInstance() (string, bool) {
	dir := newPlatform().dataDir()
	b, err := os.ReadFile(dir + string(os.PathSeparator) + "instance.url")
	if err != nil {
		return "", false
	}
	url := string(b)
	c := &http.Client{Timeout: 800 * time.Millisecond}
	resp, err := c.Get(trimURL(url) + "/api/state?t=" + tokenOf(url))
	if err != nil {
		return "", false
	}
	resp.Body.Close()
	return url, resp.StatusCode == 200
}

func writeInstance(dir, url string) {
	os.WriteFile(dir+string(os.PathSeparator)+"instance.url", []byte(url), 0o600)
}
func removeInstance(dir string) { os.Remove(dir + string(os.PathSeparator) + "instance.url") }

func trimURL(u string) string {
	if i := indexOf(u, "/?t="); i >= 0 {
		return u[:i]
	}
	return u
}
func tokenOf(u string) string {
	if i := indexOf(u, "t="); i >= 0 {
		return u[i+2:]
	}
	return ""
}
func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
