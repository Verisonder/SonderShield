package shield

import "encoding/binary"

// IP + UDP for the tun device. Only DNS to the virtual resolver is routed into the
// tunnel, so this handles UDP over IPv4 and IPv6 and drops everything else.

type udpPacket struct {
	v6      bool
	src     []byte // 4 or 16 bytes
	dst     []byte
	srcPort uint16
	dstPort uint16
	payload []byte
}

func parseUDP(pkt []byte) (udpPacket, bool) {
	var p udpPacket
	if len(pkt) < 1 {
		return p, false
	}
	var udp []byte
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return p, false
		}
		ihl := int(pkt[0]&0x0F) * 4
		total := int(binary.BigEndian.Uint16(pkt[2:4]))
		if ihl < 20 || total < ihl || total > len(pkt) || pkt[9] != 17 {
			return p, false
		}
		// Fragments: DNS queries never are, and reassembly isn't worth it.
		if binary.BigEndian.Uint16(pkt[6:8])&0x3FFF != 0 {
			return p, false
		}
		p.src, p.dst = pkt[12:16], pkt[16:20]
		udp = pkt[ihl:total]
	case 6:
		if len(pkt) < 40 || pkt[6] != 17 { // no extension headers expected
			return p, false
		}
		plen := int(binary.BigEndian.Uint16(pkt[4:6]))
		if 40+plen > len(pkt) {
			return p, false
		}
		p.v6 = true
		p.src, p.dst = pkt[8:24], pkt[24:40]
		udp = pkt[40 : 40+plen]
	default:
		return p, false
	}
	if len(udp) < 8 {
		return p, false
	}
	ulen := int(binary.BigEndian.Uint16(udp[4:6]))
	if ulen < 8 || ulen > len(udp) {
		return p, false
	}
	p.srcPort = binary.BigEndian.Uint16(udp[0:2])
	p.dstPort = binary.BigEndian.Uint16(udp[2:4])
	p.payload = udp[8:ulen]
	return p, true
}

// reply builds the packet going back to the sender of p, carrying payload.
func (p udpPacket) reply(payload []byte) []byte {
	ulen := 8 + len(payload)
	var out []byte
	var udpOff int
	if p.v6 {
		out = make([]byte, 40+ulen)
		out[0] = 0x60
		binary.BigEndian.PutUint16(out[4:6], uint16(ulen))
		out[6] = 17
		out[7] = 64
		copy(out[8:24], p.dst)
		copy(out[24:40], p.src)
		udpOff = 40
	} else {
		out = make([]byte, 20+ulen)
		out[0] = 0x45
		binary.BigEndian.PutUint16(out[2:4], uint16(20+ulen))
		out[6] = 0x40 // don't fragment
		out[8] = 64
		out[9] = 17
		copy(out[12:16], p.dst)
		copy(out[16:20], p.src)
		binary.BigEndian.PutUint16(out[10:12], ^fold(sum(out[:20], 0)))
		udpOff = 20
	}
	u := out[udpOff:]
	binary.BigEndian.PutUint16(u[0:2], p.dstPort)
	binary.BigEndian.PutUint16(u[2:4], p.srcPort)
	binary.BigEndian.PutUint16(u[4:6], uint16(ulen))
	copy(u[8:], payload)

	// Pseudo-header checksum (required on IPv6, done on IPv4 too).
	var s uint32
	s = sum(p.dst, s)
	s = sum(p.src, s)
	s += 17
	s += uint32(ulen)
	s = sum(u, s)
	c := ^fold(s)
	if c == 0 {
		c = 0xFFFF
	}
	binary.BigEndian.PutUint16(u[6:8], c)
	return out
}

func sum(b []byte, s uint32) uint32 {
	n := len(b)
	for i := 0; i+1 < n; i += 2 {
		s += uint32(b[i])<<8 | uint32(b[i+1])
	}
	if n%2 == 1 {
		s += uint32(b[n-1]) << 8
	}
	return s
}

func fold(s uint32) uint16 {
	for s>>16 != 0 {
		s = s&0xFFFF + s>>16
	}
	return uint16(s)
}
