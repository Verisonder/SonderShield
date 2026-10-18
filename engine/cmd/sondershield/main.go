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
	// Commands the installer runs (no window).
	if hasFlag("--uninstall") {
		plat := newPlatform()
		plat.restoreSystemDNS()
		plat.clearFirewall()
		plat.setAutostart(false, "")
		return
	}
	if i := flagIndex("--autostart"); i >= 0 && i+1 < len(os.Args) {
		exe, _ := os.Executable()
		newPlatform().setAutostart(os.Args[i+1] == "on", exe)
		return
	}
	// If another copy is already running, just open its window and exit.
	if url, ok := existingInstance(); ok {
		if hasFlag("--startup") || hasFlag("--tray") {
			return // started with Windows but already running: stay quiet
		}
		// Ask the running copy to bring its window forward; fall back to a browser.
		if !showExisting(url) {
			openWindow(url)
		}
		return
	}
	// Started by the Run entry at sign-in: hand over to the task that has full rights.
	if hasFlag("--startup") && startupLaunch() {
		return
	}
	// Blocking DNS on port 53 and changing network settings need administrator rights.
	if !ensureAdmin() {
		return // we relaunched elevated, or the user declined
	}

	cleanupOldUpdate()
	plat := newPlatform()
	if exe, err := os.Executable(); err == nil {
		plat.fixAutostart(exe)
	}
	a := newApp(plat)
	// A crash or power cut while protection was on can leave Windows pointed at us.
	if plat.pendingRestore() && !a.config().On {
		plat.restoreSystemDNS()
	}
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

	// The window (and on Windows the tray icon). Started by "start with Windows" (--tray),
	// it stays hidden in the tray. Returns when the user quits.
	runFrontend(a, srv, hasFlag("--tray") || hasFlag("--startup"))
	time.Sleep(200 * time.Millisecond)
}

func flagIndex(f string) int {
	for i, a := range os.Args {
		if a == f {
			return i
		}
	}
	return -1
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

func showExisting(url string) bool {
	c := &http.Client{Timeout: 2 * time.Second}
	req, _ := http.NewRequest("POST", trimURL(url)+"/api/show", nil)
	req.Header.Set("X-Token", tokenOf(url))
	resp, err := c.Do(req)
	if err != nil {
		return false
	}
	resp.Body.Close()
	return resp.StatusCode == 204
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
