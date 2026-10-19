//go:build linux

package main

import (
	"bufio"
	"encoding/hex"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The connection list on Linux: sockets from /proc/net/tcp{,6} (or udp), and their owners
// found through each process's open file descriptors. Needs root to see every process,
// which the service has.

type sockRow struct {
	laddr, raddr net.IP
	lport, rport int
	state        string
	inode        string
}

func readSockets(proto string) []sockRow {
	var out []sockRow
	for _, f := range []string{"/proc/net/" + proto, "/proc/net/" + proto + "6"} {
		fh, err := os.Open(f)
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(fh)
		sc.Scan() // header
		for sc.Scan() {
			fs := strings.Fields(sc.Text())
			if len(fs) < 10 {
				continue
			}
			la, lp := parseHexAddr(fs[1])
			ra, rp := parseHexAddr(fs[2])
			if la == nil || ra == nil {
				continue
			}
			out = append(out, sockRow{laddr: la, lport: lp, raddr: ra, rport: rp, state: fs[3], inode: fs[9]})
		}
		fh.Close()
	}
	return out
}

// parseHexAddr reads "0100007F:0035": the address is stored as 32-bit words in host
// (little-endian) order, the port as plain hex.
func parseHexAddr(s string) (net.IP, int) {
	a, p, ok := strings.Cut(s, ":")
	if !ok {
		return nil, 0
	}
	b, err := hex.DecodeString(a)
	if err != nil || (len(b) != 4 && len(b) != 16) {
		return nil, 0
	}
	for i := 0; i+4 <= len(b); i += 4 {
		b[i], b[i+1], b[i+2], b[i+3] = b[i+3], b[i+2], b[i+1], b[i]
	}
	port, _ := strconv.ParseUint(p, 16, 16)
	ip := net.IP(b)
	if v4 := ip.To4(); v4 != nil {
		ip = v4
	}
	return ip, int(port)
}

type procInfo struct {
	pid        int
	name, path string
}

var owners struct {
	sync.Mutex
	at time.Time
	m  map[string]procInfo
}

// socketOwners maps socket inode → owning process. Walking /proc is the slow part, and the
// window asks several times a second, so a result is reused for two seconds.
func socketOwners() map[string]procInfo {
	owners.Lock()
	defer owners.Unlock()
	if owners.m != nil && time.Since(owners.at) < 2*time.Second {
		return owners.m
	}
	owners.m, owners.at = scanOwners(), time.Now()
	return owners.m
}

func scanOwners() map[string]procInfo {
	out := map[string]procInfo{}
	dirs, _ := os.ReadDir("/proc")
	for _, d := range dirs {
		pid, err := strconv.Atoi(d.Name())
		if err != nil {
			continue
		}
		base := "/proc/" + d.Name()
		fds, err := os.ReadDir(base + "/fd")
		if err != nil {
			continue
		}
		var info *procInfo
		for _, fd := range fds {
			l, err := os.Readlink(base + "/fd/" + fd.Name())
			if err != nil || !strings.HasPrefix(l, "socket:[") {
				continue
			}
			ino := l[len("socket:[") : len(l)-1]
			if _, seen := out[ino]; seen {
				continue
			}
			if info == nil {
				pi := describeProcess(pid)
				info = &pi
			}
			out[ino] = *info
		}
	}
	return out
}

// describeProcess: the program's path and a friendly name — the app's name from its
// .desktop file when there is one (Firefox, Brave…), else the program file name.
func describeProcess(pid int) procInfo {
	base := "/proc/" + strconv.Itoa(pid)
	path, _ := os.Readlink(base + "/exe")
	path = strings.TrimSuffix(path, " (deleted)")
	name := ""
	if path != "" {
		name = filepath.Base(path)
	} else if b, err := os.ReadFile(base + "/comm"); err == nil {
		name = strings.TrimSpace(string(b))
	}
	if d := desktopFor(path, name); d != nil && d.name != "" {
		name = d.name
	}
	return procInfo{pid: pid, name: name, path: path}
}
