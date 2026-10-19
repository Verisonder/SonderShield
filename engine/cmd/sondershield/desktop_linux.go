//go:build linux

package main

import (
	"bufio"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Friendly names and icons for programs, from the desktop's .desktop files (the same ones
// the app launcher uses). A running program is matched by its file name, by its folder
// (Brave, Chrome… run from /opt/<app>/), or by the window class.

type desktopEntry struct {
	name, icon string
}

var desk struct {
	sync.Mutex
	built time.Time
	byKey map[string]*desktopEntry
	byDir map[string]*desktopEntry
}

func ownerHome() string {
	if id := os.Getenv("SS_OWNER"); id != "" {
		if u, err := user.LookupId(id); err == nil {
			return u.HomeDir
		}
	}
	if os.Geteuid() != 0 {
		if h, err := os.UserHomeDir(); err == nil {
			return h
		}
	}
	return ""
}

func shareDirs() []string {
	dirs := []string{"/usr/share", "/usr/local/share", "/var/lib/flatpak/exports/share", "/var/lib/snapd/desktop"}
	if h := ownerHome(); h != "" {
		dirs = append(dirs, h+"/.local/share", h+"/.local/share/flatpak/exports/share")
	}
	return dirs
}

// Common program folders: matching by folder only makes sense for an app's own folder.
var sharedBinDirs = map[string]bool{
	"/usr/bin": true, "/bin": true, "/usr/sbin": true, "/sbin": true, "/usr/local/bin": true,
	"/usr/libexec": true, "/usr/lib": true, "/usr/lib64": true, "/app/bin": true, "/snap/bin": true,
}

func buildDesktopIndex() {
	byKey := map[string]*desktopEntry{}
	byDir := map[string]*desktopEntry{}
	add := func(m map[string]*desktopEntry, k string, e *desktopEntry) {
		k = strings.ToLower(strings.TrimSpace(k))
		if k != "" && m[k] == nil {
			m[k] = e
		}
	}
	// Two passes: apps shown in the launcher win over hidden helper entries.
	for pass := 0; pass < 2; pass++ {
		for _, sd := range shareDirs() {
			files, _ := filepath.Glob(sd + "/applications/*.desktop")
			for _, f := range files {
				kv := readDesktop(f)
				if kv == nil || kv["Hidden"] == "true" || kv["Type"] != "Application" {
					continue
				}
				hidden := kv["NoDisplay"] == "true"
				if (pass == 0) == hidden {
					continue
				}
				e := &desktopEntry{name: kv["Name"], icon: kv["Icon"]}
				id := strings.TrimSuffix(filepath.Base(f), ".desktop")
				add(byKey, id, e)
				if i := strings.LastIndex(id, "."); i >= 0 {
					add(byKey, id[i+1:], e)
				}
				add(byKey, kv["StartupWMClass"], e)
				for _, cmd := range []string{kv["TryExec"], execProgram(kv["Exec"])} {
					if cmd == "" {
						continue
					}
					add(byKey, filepath.Base(cmd), e)
					full := cmd
					if !filepath.IsAbs(full) {
						if p, err := lookPathIn(full); err == nil {
							full = p
						}
					}
					if real, err := filepath.EvalSymlinks(full); err == nil {
						add(byKey, filepath.Base(real), e)
						if d := filepath.Dir(real); !sharedBinDirs[d] {
							add(byDir, d, e)
						}
					}
				}
			}
		}
	}
	desk.byKey, desk.byDir, desk.built = byKey, byDir, time.Now()
}

// lookPathIn: like exec.LookPath but with a standard PATH (the service has a bare one).
func lookPathIn(name string) (string, error) {
	for _, d := range []string{"/usr/local/bin", "/usr/bin", "/bin", "/usr/local/sbin", "/usr/sbin", "/snap/bin"} {
		p := d + "/" + name
		if st, err := os.Stat(p); err == nil && !st.IsDir() {
			return p, nil
		}
	}
	return "", os.ErrNotExist
}

// execProgram: the program in an Exec= line, skipping "env VAR=x" prefixes.
func execProgram(exec string) string {
	for _, f := range strings.Fields(exec) {
		f = strings.Trim(f, `"`)
		if f == "env" || strings.Contains(f, "=") {
			continue
		}
		return f
	}
	return ""
}

func readDesktop(path string) map[string]string {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	kv := map[string]string{}
	in := false
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(l, "[") {
			in = l == "[Desktop Entry]"
			continue
		}
		if !in {
			continue
		}
		if k, v, ok := strings.Cut(l, "="); ok {
			k = strings.TrimSpace(k)
			if _, dup := kv[k]; !dup {
				kv[k] = strings.TrimSpace(v)
			}
		}
	}
	return kv
}

// desktopFor finds the .desktop entry for a running program (path may be empty).
func desktopFor(path, name string) *desktopEntry {
	desk.Lock()
	defer desk.Unlock()
	if desk.byKey == nil || time.Since(desk.built) > 5*time.Minute {
		buildDesktopIndex()
	}
	if path != "" {
		if e := desk.byKey[strings.ToLower(filepath.Base(path))]; e != nil {
			return e
		}
		for d := filepath.Dir(path); d != "/" && d != "."; d = filepath.Dir(d) {
			if e := desk.byDir[d]; e != nil {
				return e
			}
		}
	}
	if name != "" {
		return desk.byKey[strings.ToLower(name)]
	}
	return nil
}

// desktopIcon returns the program's icon file (PNG or SVG), or nil.
func desktopIcon(path string) []byte {
	e := desktopFor(path, "")
	if e == nil || e.icon == "" {
		return nil
	}
	if filepath.IsAbs(e.icon) {
		return readSmall(e.icon)
	}
	sizes := []string{"256x256", "128x128", "96x96", "64x64", "48x48", "512x512", "scalable"}
	for _, sd := range shareDirs() {
		for _, sz := range sizes {
			for _, ext := range []string{".png", ".svg"} {
				if b := readSmall(sd + "/icons/hicolor/" + sz + "/apps/" + e.icon + ext); b != nil {
					return b
				}
			}
		}
		for _, ext := range []string{".png", ".svg"} {
			if b := readSmall(sd + "/pixmaps/" + e.icon + ext); b != nil {
				return b
			}
		}
	}
	return nil
}

func readSmall(p string) []byte {
	st, err := os.Stat(p)
	if err != nil || st.IsDir() || st.Size() > 2<<20 {
		return nil
	}
	b, _ := os.ReadFile(p)
	return b
}
