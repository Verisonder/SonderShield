//go:build linux

package main

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// On Linux SonderShield is two parts in one program:
//
//   - the service (--daemon), run by systemd as root: the engine, the nftables rules and
//     the window's local web interface. It keeps protecting with no window open.
//   - the launcher (no flags), run by the user from the AppImage: installs or updates the
//     service when needed (one password prompt), starts it, and opens the window.
//
// The window is a small WebKitGTK program shipped in the AppImage (sondershield-window);
// without WebKitGTK on the system it falls back to a Chromium-family app window.

func execPath() (string, error) { return os.Executable() }

func ensureAdmin() bool {
	if os.Geteuid() != 0 {
		fatal("the service must run as root (it is started by systemd)")
		return false
	}
	return true
}

func startupLaunch() bool { return false }

func openWindow(url string) { exec.Command("xdg-open", url).Start() }

func fatal(msg string) { fmt.Fprintln(os.Stderr, "SonderShield:", msg) }

// shareInstance: the window address is written by root; hand it to the user who installed
// SonderShield, so their launcher (and only theirs) can read it.
func shareInstance(path string) {
	if uid, err := strconv.Atoi(os.Getenv("SS_OWNER")); err == nil {
		os.Chown(path, uid, -1)
	}
}

func osMain() bool {
	switch {
	case hasFlag("--version"):
		fmt.Println(version)
	case hasFlag("--cleanup"): // systemd ExecStopPost: never leave DNS pointing at nothing
		dropRules()
	case hasFlag("--install"):
		if os.Geteuid() != 0 {
			fmt.Fprintln(os.Stderr, "SonderShield: --install needs root (use sudo)")
			os.Exit(1)
		}
		if err := install(installOwner()); err != nil {
			fmt.Fprintln(os.Stderr, "SonderShield:", err)
			os.Exit(1)
		}
		fmt.Println("SonderShield is installed and running.")
	case hasFlag("--uninstall"):
		if os.Geteuid() != 0 {
			if err := runAsRoot("--uninstall"); err != nil {
				fmt.Fprintln(os.Stderr, "SonderShield:", err)
				os.Exit(1)
			}
			return true
		}
		uninstall()
		fmt.Println("SonderShield is removed. Your DNS is back to normal.")
	case hasFlag("--daemon"):
		return false // the service: carry on in main
	default:
		launch()
	}
	return true
}

// ---- the service: SIGTERM from systemd (shutdown, restart, update) ----

// runFrontend for the service: no window here. Quit (from the window) stops protection and
// exits; a stop from systemd only takes the rules down, so protection resumes next start.
func runFrontend(a *App, srv *server, hidden bool) {
	quit := make(chan struct{})
	srv.onQuit = func() { a.stop(); close(quit) }
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	select {
	case <-quit:
	case <-sig:
		a.saveStats()
		a.plat.restoreSystemDNS()
		a.plat.setDNSLockdown(false, "")
		a.plat.clearFirewall()
		a.eng.Stop()
	}
}

// ---- install / uninstall (as root) ----

func installOwner() string {
	if i := flagIndex("--owner"); i >= 0 && i+1 < len(os.Args) {
		return os.Args[i+1]
	}
	for _, k := range []string{"PKEXEC_UID", "SUDO_UID"} {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return ""
}

const unitTemplate = `[Unit]
Description=SonderShield (blocks ads, trackers and malware)
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
ExecStart=%[1]s --daemon
ExecStopPost=%[1]s --cleanup
Environment=SS_OWNER=%[2]s
RuntimeDirectory=sondershield
RuntimeDirectoryMode=0755
StateDirectory=sondershield
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
`

const polkitTemplate = `// SonderShield: lets %[1]s start and stop the SonderShield service without a password.
polkit.addRule(function(action, subject) {
    if (action.id == "org.freedesktop.systemd1.manage-units" &&
        action.lookup("unit") == "sondershield.service" &&
        subject.user == "%[1]s") {
        return polkit.Result.YES;
    }
});
`

// install copies this program to /usr/local/bin, writes the systemd service and a polkit
// rule for the user, then (re)starts the service. Run again on every update.
func install(owner string) error {
	src, err := os.Executable()
	if err != nil {
		return err
	}
	if src != installBin {
		tmp := installBin + ".new"
		if err := copyFile(src, tmp, 0o755); err != nil {
			return fmt.Errorf("could not copy the program: %v", err)
		}
		if err := os.Rename(tmp, installBin); err != nil {
			return err
		}
		relabel(installBin)
	}
	first := !fileExists(unitPath)
	if err := os.WriteFile(unitPath, []byte(fmt.Sprintf(unitTemplate, installBin, owner)), 0o644); err != nil {
		return err
	}
	if u, err := user.LookupId(owner); err == nil && owner != "0" {
		os.MkdirAll(filepath.Dir(polkitPath), 0o755)
		os.WriteFile(polkitPath, []byte(fmt.Sprintf(polkitTemplate, u.Username)), 0o644)
	}
	if err := systemctl("daemon-reload"); err != nil {
		return err
	}
	if first { // like Windows: starts with the computer unless switched off in Settings
		systemctl("enable", unitName)
	}
	if nftPath() == "" {
		fmt.Fprintln(os.Stderr, "SonderShield: warning:", errNoNft)
	}
	return systemctl("restart", unitName)
}

// relabel gives the copied program the right SELinux label (Fedora), if SELinux is there.
func relabel(path string) {
	if p, err := exec.LookPath("restorecon"); err == nil {
		exec.Command(p, path).Run()
	}
}

func uninstall() {
	systemctl("disable", "--now", unitName)
	dropRules()
	nftDrop(tblKill)
	os.Remove(unitPath)
	os.Remove(polkitPath)
	systemctl("daemon-reload")
	os.Remove(installBin)
	os.RemoveAll(stateDir)
	if h := homeOf(installOwner()); h != "" {
		os.Remove(h + "/.local/share/applications/sondershield.desktop")
		os.Remove(h + "/.local/share/icons/hicolor/256x256/apps/sondershield.png")
	}
}

func homeOf(uid string) string {
	if u, err := user.LookupId(uid); err == nil {
		return u.HomeDir
	}
	return ""
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	os.Remove(dst)
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

// ---- the launcher (as the user) ----

func launch() {
	if os.Geteuid() == 0 {
		showError("Open SonderShield as your normal user, not as root. It asks for your password when it needs it.")
		return
	}
	integrateDesktop()
	if raiseExistingWindow() {
		return
	}
	if needsInstall() {
		if err := runAsRoot("--install", "--owner", strconv.Itoa(os.Getuid())); err != nil {
			showError(err.Error())
			return
		}
	}
	url, ok := existingInstance()
	if !ok {
		if err := exec.Command("systemctl", "start", unitName).Run(); err != nil {
			// No polkit rule yet (or it was removed): reinstalling puts it back.
			if err := runAsRoot("--install", "--owner", strconv.Itoa(os.Getuid())); err != nil {
				showError(err.Error())
				return
			}
		}
		for i := 0; i < 60 && !ok; i++ {
			time.Sleep(250 * time.Millisecond)
			url, ok = existingInstance()
		}
	}
	if !ok {
		showError("The SonderShield service didn't start. To see why, run: journalctl -u sondershield")
		return
	}
	runWindow(url)
}

// needsInstall: no service yet, or this AppImage is newer than the installed copy.
func needsInstall() bool {
	if !fileExists(unitPath) || !fileExists(installBin) {
		return true
	}
	out, err := exec.Command(installBin, "--version").Output()
	if err != nil {
		return true
	}
	inst := strings.TrimSpace(string(out))
	if version == "dev" {
		return inst != "dev" && os.Getenv("SS_REINSTALL") != ""
	}
	// Same version, or the service already updated itself past this AppImage: keep it.
	return inst != version && !newer(inst, version)
}

// runAsRoot runs this program with root rights through pkexec (the desktop's password
// prompt). The AppImage's files can't be read by root, so a copy is run from ~/.cache.
func runAsRoot(args ...string) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}
	dir := filepath.Join(userCacheDir(), "sondershield")
	os.MkdirAll(dir, 0o755)
	tmp := filepath.Join(dir, "sondershield-setup")
	if err := copyFile(self, tmp, 0o755); err != nil {
		return err
	}
	defer os.Remove(tmp)
	how := "sudo \"" + appPath() + "\" " + strings.Join(args, " ")
	pk, err := exec.LookPath("pkexec")
	if err != nil {
		return fmt.Errorf("SonderShield needs your password once to install its service, but pkexec (polkit) isn't installed. Run this in a terminal instead:\n%s", how)
	}
	out, err := exec.Command(pk, append([]string{tmp}, args...)...).CombinedOutput()
	if err == nil {
		return nil
	}
	msg := strings.TrimSpace(string(out))
	if ee, ok := err.(*exec.ExitError); ok && (ee.ExitCode() == 126 || ee.ExitCode() == 127) {
		if strings.Contains(strings.ToLower(msg), "agent") {
			return fmt.Errorf("No password prompt is running on this desktop (a polkit agent — on Hyprland, hyprpolkitagent). Start one, or run this in a terminal:\n%s", how)
		}
		return fmt.Errorf("SonderShield needs your password once to install its service. Open it again to retry, or run this in a terminal:\n%s", how)
	}
	return fmt.Errorf("Setup failed: %s", msg)
}

func userCacheDir() string {
	if d, err := os.UserCacheDir(); err == nil {
		return d
	}
	return os.TempDir()
}

// appPath: the AppImage file itself when run from one, else this program.
func appPath() string {
	if p := os.Getenv("APPIMAGE"); p != "" {
		return p
	}
	p, _ := os.Executable()
	return p
}

// appDir: the AppImage's contents (window helper, icon), or this program's folder.
func appDir() string {
	if d := os.Getenv("APPDIR"); d != "" {
		return d
	}
	p, _ := os.Executable()
	return filepath.Dir(p)
}

func appIconFile() string {
	for _, p := range []string{appDir() + "/sondershield.png", appDir() + "/usr/share/icons/hicolor/256x256/apps/sondershield.png"} {
		if fileExists(p) {
			return p
		}
	}
	return ""
}

// integrateDesktop adds SonderShield to the app launcher (rofi, wofi, GNOME, KDE…) pointing
// at this AppImage, so it can be opened like any installed app.
func integrateDesktop() {
	if os.Getenv("APPIMAGE") == "" {
		return
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return
	}
	entry := "[Desktop Entry]\nType=Application\nName=SonderShield\nGenericName=Ad blocker\n" +
		"Comment=Block ads, trackers and malware on this computer\n" +
		"Exec=\"" + appPath() + "\"\nIcon=sondershield\nTerminal=false\nCategories=Network;Security;\n" +
		"Keywords=adblock;dns;firewall;privacy;\nStartupWMClass=sondershield\n"
	df := home + "/.local/share/applications/sondershield.desktop"
	if b, err := os.ReadFile(df); err != nil || string(b) != entry {
		os.MkdirAll(filepath.Dir(df), 0o755)
		os.WriteFile(df, []byte(entry), 0o644)
	}
	if icon := appIconFile(); icon != "" {
		dst := home + "/.local/share/icons/hicolor/256x256/apps/sondershield.png"
		if !fileExists(dst) {
			os.MkdirAll(filepath.Dir(dst), 0o755)
			copyFile(icon, dst, 0o644)
		}
	}
}

// ---- the window ----

func windowPidFile() string {
	d := os.Getenv("XDG_RUNTIME_DIR")
	if d == "" {
		d = userCacheDir()
	}
	return filepath.Join(d, "sondershield-window.pid")
}

// raiseExistingWindow: a window is already open, so bring it forward instead of a second.
func raiseExistingWindow() bool {
	b, err := os.ReadFile(windowPidFile())
	if err != nil {
		return false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || pid == os.Getpid() {
		return false
	}
	cmdline, _ := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if !strings.Contains(strings.ToLower(string(cmdline)), "sondershield") {
		return false
	}
	return syscall.Kill(pid, syscall.SIGUSR1) == nil
}

// runWindow shows the interface until the window is closed (protection keeps running),
// or until the service goes away (Quit in the window).
func runWindow(url string) {
	os.WriteFile(windowPidFile(), []byte(strconv.Itoa(os.Getpid())), 0o644)
	defer os.Remove(windowPidFile())

	cmd := windowCommand(url)
	if cmd == nil {
		openWindow(url)
		return
	}
	if err := cmd.Start(); err != nil {
		openWindow(url)
		return
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	raise := make(chan os.Signal, 1)
	signal.Notify(raise, syscall.SIGUSR1)
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	started := time.Now()
	misses := 0
	for {
		select {
		case err := <-done:
			// The WebKit window couldn't start (library missing): try a browser window.
			if err != nil && time.Since(started) < 3*time.Second && strings.HasSuffix(cmd.Path, "sondershield-window") {
				if b := browserCommand(url); b != nil && b.Start() == nil {
					cmd = b
					started = time.Now()
					go func(c *exec.Cmd) { done <- c.Wait() }(b)
					continue
				}
				openWindow(url)
			}
			return
		case <-raise:
			cmd.Process.Signal(syscall.SIGUSR1)
		case <-tick.C:
			now, ok := existingInstance()
			switch {
			case ok && now != url && strings.HasSuffix(cmd.Path, "sondershield-window"):
				// The service restarted (an update) on a new address: reopen on it.
				misses = 0
				url = now
				cmd.Process.Signal(syscall.SIGTERM)
				<-done
				cmd = windowCommand(url)
				if cmd == nil || cmd.Start() != nil {
					return
				}
				started = time.Now()
				go func(c *exec.Cmd) { done <- c.Wait() }(cmd)
			case ok:
				misses = 0
			default:
				if misses++; misses >= 3 { // Quit was pressed: close the window too
					cmd.Process.Signal(syscall.SIGTERM)
				}
			}
		}
	}
}

func windowCommand(url string) *exec.Cmd {
	for _, h := range []string{appDir() + "/usr/bin/sondershield-window", appDir() + "/sondershield-window"} {
		if fileExists(h) {
			return exec.Command(h, url, appIconFile())
		}
	}
	return browserCommand(url)
}

// browserCommand: a Chromium-family browser in app mode (its own window, no tabs), with
// its own profile so it opens as a separate window and stays open until closed.
func browserCommand(url string) *exec.Cmd {
	for _, b := range []string{"brave-browser", "brave", "chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "microsoft-edge", "vivaldi"} {
		if p, err := exec.LookPath(b); err == nil {
			prof := filepath.Join(userDataDir(), "window")
			return exec.Command(p, "--app="+url, "--user-data-dir="+prof, "--class=sondershield",
				"--window-size=1180,800", "--no-first-run", "--no-default-browser-check")
		}
	}
	return nil
}

// showError shows a message before there is any interface: in the SonderShield window if
// it can open, else as a desktop notification.
func showError(msg string) {
	fatal(msg)
	page := `<!doctype html><meta charset="utf-8"><title>SonderShield</title><style>
body{margin:0;background:#000;color:#fff;font:15px/1.5 system-ui,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
.b{max-width:560px;padding:32px}.k{color:#3B82FF;letter-spacing:.18em;font-weight:700;font-size:12px}
h1{font-size:30px;margin:10px 0 14px}pre{white-space:pre-wrap;background:#1C1C1C;border-radius:14px;padding:14px;font:13px/1.5 ui-monospace,monospace;color:#ddd}
</style><div class="b"><div class="k">▲ SONDERSHIELD</div><h1>Not started yet</h1><pre>` + htmlEscape(msg) + `</pre></div>`
	f := filepath.Join(userCacheDir(), "sondershield", "message.html")
	os.MkdirAll(filepath.Dir(f), 0o755)
	if os.WriteFile(f, []byte(page), 0o644) == nil {
		for _, h := range []string{appDir() + "/usr/bin/sondershield-window", appDir() + "/sondershield-window"} {
			if fileExists(h) && exec.Command(h, "file://"+f, appIconFile()).Run() == nil {
				return
			}
		}
	}
	exec.Command("notify-send", "-a", "SonderShield", "SonderShield", msg).Run()
}

func htmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(s)
}
