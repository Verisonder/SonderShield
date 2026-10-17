//go:build windows

package main

import (
	"encoding/binary"
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
