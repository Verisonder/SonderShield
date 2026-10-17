//go:build windows

package main

import (
	"encoding/binary"
	"os"
	"unsafe"
)

// Machine-wide byte counters and connection counts, the same numbers Task Manager shows.
// GetIfTable gives per-adapter octet counters (32-bit, so they wrap at 4 GB); the app layer
// samples them and accumulates 64-bit totals, handling the wrap.

var procGetIfTable = iphlpapi.NewProc("GetIfTable")

const ifTypeLoopback = 24

// MIB_IFROW: only the leading fields up to the octet counters matter, but the whole struct
// is laid out so the offsets line up.
type mibIfRow struct {
	Name            [256]uint16
	Index           uint32
	Type            uint32
	Mtu             uint32
	Speed           uint32
	PhysAddrLen     uint32
	PhysAddr        [8]byte
	AdminStatus     uint32
	OperStatus      uint32
	LastChange      uint32
	InOctets        uint32
	InUcastPkts     uint32
	InNUcastPkts    uint32
	InDiscards      uint32
	InErrors        uint32
	InUnknownProtos uint32
	OutOctets       uint32
	OutUcastPkts    uint32
	OutNUcastPkts   uint32
	OutDiscards     uint32
	OutErrors       uint32
	OutQLen         uint32
	DescrLen        uint32
	Descr           [256]byte
}

func (p *windowsPlatform) rawNetCounters() []NetCounter {
	var size uint32
	procGetIfTable.Call(0, uintptr(unsafe.Pointer(&size)), 0)
	if size == 0 {
		return nil
	}
	buf := make([]byte, size)
	r, _, _ := procGetIfTable.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)), 0)
	if r != 0 || len(buf) < 4 {
		return nil
	}
	n := int(binary.LittleEndian.Uint32(buf[:4]))
	if n <= 0 {
		return nil
	}
	rows := unsafe.Slice((*mibIfRow)(unsafe.Pointer(&buf[4])), n)
	out := make([]NetCounter, 0, n)
	for i := 0; i < n; i++ {
		row := rows[i]
		if row.Type == ifTypeLoopback {
			continue
		}
		out = append(out, NetCounter{Index: row.Index, In: row.InOctets, Out: row.OutOctets})
	}
	return out
}

// connCounts reads the TCP tables and counts real outbound connections and distinct apps.
func (p *windowsPlatform) connCounts() (int, int) {
	pids := map[uint32]bool{}
	conns := 0
	tally := func(state, remotePort, pid uint32) {
		st := tcpStates[state]
		if st == "" || st == "listening" || remotePort == 0 {
			return
		}
		conns++
		pids[pid] = true
	}
	if buf := tcpTable(afInet); len(buf) >= 4 {
		n := int(binary.LittleEndian.Uint32(buf[:4]))
		rows := unsafe.Slice((*tcpRow4)(unsafe.Pointer(&buf[4])), n)
		for i := 0; i < n; i++ {
			tally(rows[i].state, rows[i].remotePort, rows[i].owningPid)
		}
	}
	if buf := tcpTable(afInet6); len(buf) >= 4 {
		n := int(binary.LittleEndian.Uint32(buf[:4]))
		rows := unsafe.Slice((*tcpRow6)(unsafe.Pointer(&buf[4])), n)
		for i := 0; i < n; i++ {
			tally(rows[i].state, rows[i].remotePort, rows[i].owningPid)
		}
	}
	return conns, len(pids)
}

var procGetExtendedUdpTbl = iphlpapi.NewProc("GetExtendedUdpTable")

const udpTableOwnerPid = 1

// port53Owner finds who else is listening on UDP port 53 (IPv4 or IPv6), which is what makes
// SonderShield fall back to its own adapter. Returns "" when nobody else has it.
func (p *windowsPlatform) port53Owner() string {
	self := uint32(os.Getpid())
	find := func(af uint32, rowSize, portOff, pidOff int) uint32 {
		var size uint32
		procGetExtendedUdpTbl.Call(0, uintptr(unsafe.Pointer(&size)), 0, uintptr(af), udpTableOwnerPid, 0)
		if size == 0 {
			return 0
		}
		buf := make([]byte, size)
		r, _, _ := procGetExtendedUdpTbl.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)), 0, uintptr(af), udpTableOwnerPid, 0)
		if r != 0 || len(buf) < 4 {
			return 0
		}
		n := int(binary.LittleEndian.Uint32(buf[:4]))
		for i := 0; i < n; i++ {
			off := 4 + i*rowSize
			if off+rowSize > len(buf) {
				break
			}
			row := buf[off : off+rowSize]
			port := int(row[portOff])<<8 | int(row[portOff+1]) // network order in the low bytes
			pid := binary.LittleEndian.Uint32(row[pidOff : pidOff+4])
			if port == 53 && pid != self && pid != 0 {
				return pid
			}
		}
		return 0
	}
	// MIB_UDPROW_OWNER_PID: addr(4) port(4) pid(4). MIB_UDP6ROW_OWNER_PID: addr(16) scope(4) port(4) pid(4).
	pid := find(afInet, 12, 4, 8)
	if pid == 0 {
		pid = find(afInet6, 28, 20, 24)
	}
	if pid == 0 {
		return ""
	}
	if info, ok := pidNames()[pid]; ok && info.name != "" {
		return info.name
	}
	return "process " + itoa(pid)
}
