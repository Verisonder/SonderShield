package main

import (
	"archive/zip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// version is the build's version, set at build time with -ldflags "-X main.version=1.0.2".
// "dev" means a local build, which never reports an update as available.
var version = "dev"

// linuxAsset is the release file the Linux service updates itself from.
const linuxAsset = "sondershield-linux-x86_64"

const releasesAPI = "https://api.github.com/repos/Verisonder/SonderShield/releases/latest"

type updateInfo struct {
	Current   string `json:"current"`
	Latest    string `json:"latest"`
	Available bool   `json:"available"`
	Notes     string `json:"notes"`
	url       string // the Windows zip to download
}

type ghRelease struct {
	TagName string `json:"tag_name"`
	Body    string `json:"body"`
	Assets  []struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
	} `json:"assets"`
}

// checkUpdate asks GitHub for the latest release and compares it to this build.
func checkUpdate(ctx context.Context) (updateInfo, error) {
	info := updateInfo{Current: version}
	req, _ := http.NewRequestWithContext(ctx, "GET", releasesAPI, nil)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "SonderShield")
	c := &http.Client{Timeout: 20 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return info, errors.New("could not reach GitHub")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return info, errors.New("GitHub returned " + strconv.Itoa(resp.StatusCode))
	}
	var r ghRelease
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return info, err
	}
	info.Latest = strings.TrimPrefix(r.TagName, "v")
	info.Notes = r.Body
	// Prefer the plain .exe; older releases only have the zip.
	for _, a := range r.Assets {
		if runtime.GOOS == "linux" {
			// The service updates itself from the plain program (the AppImage's contents).
			if a.Name == linuxAsset {
				info.url = a.URL
			}
			continue
		}
		if strings.HasPrefix(a.Name, "SonderShield-Windows") && strings.HasSuffix(a.Name, ".zip") && info.url == "" {
			info.url = a.URL
		}
	}
	for _, a := range r.Assets {
		if runtime.GOOS != "linux" && strings.HasPrefix(a.Name, "SonderShield-Windows") && strings.HasSuffix(a.Name, ".exe") {
			info.url = a.URL
		}
	}
	info.Available = version != "dev" && info.url != "" && newer(info.Latest, version)
	return info, nil
}

// newer reports whether version a is higher than b (dotted numbers, e.g. 1.0.10 > 1.0.9).
func newer(a, b string) bool {
	pa, pb := parseVer(a), parseVer(b)
	for i := 0; i < 4; i++ {
		if pa[i] != pb[i] {
			return pa[i] > pb[i]
		}
	}
	return false
}

func parseVer(v string) [4]int {
	var out [4]int
	for i, p := range strings.SplitN(v, ".", 4) {
		n, _ := strconv.Atoi(strings.TrimFunc(p, func(r rune) bool { return r < '0' || r > '9' }))
		out[i] = n
	}
	return out
}

// applyUpdate downloads the latest Windows build, swaps this .exe for it, and relaunches.
// It returns only on failure; on success the process is replaced.
func applyUpdate(ctx context.Context, onBeforeExit func()) error {
	info, err := checkUpdate(ctx)
	if err != nil {
		return err
	}
	if !info.Available {
		return errors.New("already up to date")
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	// Download the new build (the .exe itself, or the zip it's in).
	req, _ := http.NewRequestWithContext(ctx, "GET", info.url, nil)
	req.Header.Set("User-Agent", "SonderShield")
	resp, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
	if err != nil {
		return errors.New("download failed")
	}
	defer resp.Body.Close()
	tmpZip, err := os.CreateTemp(filepath.Dir(exe), "ss-update-*.part")
	if err != nil {
		return err
	}
	defer os.Remove(tmpZip.Name())
	if _, err := io.Copy(tmpZip, io.LimitReader(resp.Body, 100<<20)); err != nil {
		tmpZip.Close()
		return err
	}
	tmpZip.Close()

	newExe := exe + ".new"
	if strings.HasSuffix(strings.ToLower(info.url), ".exe") || runtime.GOOS == "linux" {
		if err := os.Rename(tmpZip.Name(), newExe); err != nil {
			return err
		}
	} else if err := extractExe(tmpZip.Name(), newExe); err != nil {
		return err
	}
	// Hand off to the platform-specific swap (rename-and-relaunch on Windows).
	return selfReplace(exe, newExe, onBeforeExit)
}

// extractExe writes the first .exe inside the zip to dst.
func extractExe(zipPath, dst string) error {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer zr.Close()
	for _, f := range zr.File {
		if !strings.HasSuffix(strings.ToLower(f.Name), ".exe") {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		defer rc.Close()
		out, err := os.Create(dst)
		if err != nil {
			return err
		}
		_, err = io.Copy(out, io.LimitReader(rc, 200<<20))
		out.Close()
		return err
	}
	return errors.New("no .exe in the downloaded file")
}

// cleanupOldUpdate removes the previous .exe left behind after an update (called at startup).
func cleanupOldUpdate() {
	if exe, err := os.Executable(); err == nil {
		os.Remove(exe + ".old")
		os.Remove(filepath.Dir(exe) + string(os.PathSeparator) + "SonderShield.exe.new")
	}
}

// onNewVersion shows a system notice about a new version (the Windows tray balloon);
// nil where there is none. Called once per new version.
var onNewVersion func(v string)

// watchUpdates checks for a new version by itself — shortly after start, then twice a day —
// so the window can say so without the user pressing "Check for updates".
func (a *App) watchUpdates() {
	time.Sleep(20 * time.Second)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		info, err := checkUpdate(ctx)
		cancel()
		if err == nil {
			a.noteUpdate(info)
		}
		time.Sleep(12 * time.Hour)
	}
}

// noteUpdate keeps the latest check result and, the first time a version shows up, tells
// the user through the system (once per version, remembered across restarts).
func (a *App) noteUpdate(info updateInfo) {
	a.mu.Lock()
	a.update = info
	tell := info.Available && a.cfg.UpdateNotified != info.Latest
	if tell {
		a.cfg.UpdateNotified = info.Latest
	}
	cfg := a.cfg
	a.mu.Unlock()
	if tell {
		a.st.save("config.json", &cfg)
		if onNewVersion != nil {
			onNewVersion(info.Latest)
		}
	}
}
