//go:build windows

package main

import (
	"encoding/binary"
	"net"
	"sort"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Reading the computer's open connections the same way Task Manager and netstat do:
// GetExtendedTcpTable from iphlpapi, which lists every TCP connection with the process
// that owns it. No driver and no packet capture — just the OS's own connection table.

var (
	iphlpapi               = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetExtendedTcpTbl  = iphlpapi.NewProc("GetExtendedTcpTable")
)

const (
	tcpTableOwnerPidAll = 5
	afInet              = 2
	afInet6             = 23
)

// MIB_TCPROW_OWNER_PID (IPv4): six DWORDs, no padding.
type tcpRow4 struct {
	state      uint32
	localAddr  uint32
	localPort  uint32
	remoteAddr uint32
	remotePort uint32
	owningPid  uint32
}

// MIB_TCP6ROW_OWNER_PID.
type tcpRow6 struct {
	localAddr    [16]byte
	localScope   uint32
	localPort    uint32
	remoteAddr   [16]byte
	remoteScope  uint32
	remotePort   uint32
	state        uint32
	owningPid    uint32
}

var tcpStates = map[uint32]string{
	1: "closed", 2: "listening", 3: "connecting", 4: "connecting", 5: "established",
	6: "closing", 7: "closing", 8: "closing", 9: "closing", 10: "closing",
	11: "time-wait", 12: "closing",
}

func (p *windowsPlatform) listConnections() []Conn {
	var out []Conn
	names := pidNames()
	port := func(dw uint32) int { // the port sits in the low two bytes, network order
		b := make([]byte, 4)
		binary.LittleEndian.PutUint32(b, dw)
		return int(b[0])<<8 | int(b[1])
	}
	add := func(state uint32, laddr string, lport int, raddr string, rport int, pid uint32) {
		st := tcpStates[state]
		if st == "" {
			st = itoa(state)
		}
		// Only real outbound connections: skip listeners and anything with no remote.
		if st == "listening" || rport == 0 || raddr == "0.0.0.0" || raddr == "::" {
			return
		}
		app, path := names[uint32(pid)].name, names[uint32(pid)].path
		out = append(out, Conn{
			Proto: "TCP", State: st, App: app, Path: path, PID: int(pid),
			LocalAddr: laddr, LocalPort: lport, RemoteAddr: raddr, RemotePort: rport,
		})
	}

	// IPv4
	if buf := tcpTable(afInet); len(buf) >= 4 {
		n := int(binary.LittleEndian.Uint32(buf[:4]))
		rows := unsafe.Slice((*tcpRow4)(unsafe.Pointer(&buf[4])), n)
		for i := 0; i < n; i++ {
			r := rows[i]
			la, ra := make([]byte, 4), make([]byte, 4)
			binary.LittleEndian.PutUint32(la, r.localAddr)
			binary.LittleEndian.PutUint32(ra, r.remoteAddr)
			add(r.state, net.IP(la).String(), port(r.localPort),
				net.IP(ra).String(), port(r.remotePort), r.owningPid)
		}
	}
	// IPv6
	if buf := tcpTable(afInet6); len(buf) >= 4 {
		n := int(binary.LittleEndian.Uint32(buf[:4]))
		rows := unsafe.Slice((*tcpRow6)(unsafe.Pointer(&buf[4])), n)
		for i := 0; i < n; i++ {
			r := rows[i]
			add(r.state, net.IP(r.localAddr[:]).String(), port(r.localPort),
				net.IP(r.remoteAddr[:]).String(), port(r.remotePort), r.owningPid)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].App != out[j].App {
			return strings.ToLower(out[i].App) < strings.ToLower(out[j].App)
		}
		return out[i].RemoteAddr < out[j].RemoteAddr
	})
	return out
}

// tcpTable fetches the owner-PID TCP table for one address family as a raw byte buffer.
func tcpTable(af uint32) []byte {
	var size uint32
	procGetExtendedTcpTbl.Call(0, uintptr(unsafe.Pointer(&size)), 0, uintptr(af), tcpTableOwnerPidAll, 0)
	if size == 0 {
		return nil
	}
	for tries := 0; tries < 4; tries++ {
		buf := make([]byte, size)
		r, _, _ := procGetExtendedTcpTbl.Call(uintptr(unsafe.Pointer(&buf[0])),
			uintptr(unsafe.Pointer(&size)), 0, uintptr(af), tcpTableOwnerPidAll, 0)
		if r == 0 {
			return buf
		}
		if r != uintptr(windows.ERROR_INSUFFICIENT_BUFFER) {
			return nil
		}
	}
	return nil
}

type pidInfo struct{ name, path string }

// pidNames maps each process id to its app name and .exe path, in one snapshot.
func pidNames() map[uint32]pidInfo {
	m := map[uint32]pidInfo{}
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return m
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		name := windows.UTF16ToString(e.ExeFile[:])
		m[e.ProcessID] = pidInfo{name: strings.TrimSuffix(name, ".exe"), path: processPath(e.ProcessID)}
	}
	return m
}

func itoa(n uint32) string {
	if n == 0 {
		return "0"
	}
	var b [10]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
