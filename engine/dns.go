package shield

import (
	"encoding/binary"
	"errors"
	"strings"
)

// Only the parts of the DNS wire format the filter needs: the question, building a
// blocked or failed answer, and the lowest TTL of an answer for the cache.

const (
	typeA     = 1
	typeAAAA  = 28
	typeHTTPS = 65
	typeSVCB  = 64

	headerLen = 12
)

var errBadMessage = errors.New("bad dns message")

// question is the first (and in practice only) question of a query.
type question struct {
	id    uint16
	name  string // lower case, no trailing dot
	qtype uint16
	end   int // offset just past QTYPE and QCLASS
}

func parseQuestion(msg []byte) (question, error) {
	var q question
	if len(msg) < headerLen {
		return q, errBadMessage
	}
	if msg[2]&0x80 != 0 { // QR set: a response, not a query
		return q, errBadMessage
	}
	if binary.BigEndian.Uint16(msg[4:6]) < 1 {
		return q, errBadMessage
	}
	q.id = binary.BigEndian.Uint16(msg[0:2])
	name, off, err := readName(msg, headerLen)
	if err != nil {
		return q, err
	}
	if off+4 > len(msg) {
		return q, errBadMessage
	}
	q.name = name
	q.qtype = binary.BigEndian.Uint16(msg[off : off+2])
	q.end = off + 4
	return q, nil
}

// readName reads a possibly compressed name starting at off and returns it with the
// offset just past it in the original position.
func readName(msg []byte, off int) (string, int, error) {
	var b strings.Builder
	end := -1
	jumps := 0
	for {
		if off >= len(msg) {
			return "", 0, errBadMessage
		}
		l := int(msg[off])
		switch {
		case l == 0:
			if end < 0 {
				end = off + 1
			}
			return strings.ToLower(b.String()), end, nil
		case l&0xC0 == 0xC0:
			if off+1 >= len(msg) {
				return "", 0, errBadMessage
			}
			if end < 0 {
				end = off + 2
			}
			jumps++
			if jumps > 16 {
				return "", 0, errBadMessage
			}
			off = int(binary.BigEndian.Uint16(msg[off:off+2]) & 0x3FFF)
		case l&0xC0 != 0:
			return "", 0, errBadMessage
		default:
			if off+1+l > len(msg) {
				return "", 0, errBadMessage
			}
			if b.Len() > 0 {
				b.WriteByte('.')
			}
			b.Write(msg[off+1 : off+1+l])
			if b.Len() > 253 {
				return "", 0, errBadMessage
			}
			off += 1 + l
		}
	}
}

func skipName(msg []byte, off int) (int, error) {
	for {
		if off >= len(msg) {
			return 0, errBadMessage
		}
		l := int(msg[off])
		switch {
		case l == 0:
			return off + 1, nil
		case l&0xC0 == 0xC0:
			if off+2 > len(msg) {
				return 0, errBadMessage
			}
			return off + 2, nil
		case l&0xC0 != 0:
			return 0, errBadMessage
		default:
			off += 1 + l
		}
	}
}

// blockedReply answers the query with 0.0.0.0 for A, :: for AAAA and no records for
// anything else (an empty NOERROR, so apps don't retry a "missing" name elsewhere).
func blockedReply(query []byte, q question) []byte {
	out := make([]byte, q.end, q.end+28)
	copy(out, query[:q.end])
	out[2] = 0x80 | (query[2] & 0x79) // QR, keep opcode and RD
	out[3] = 0x80                     // RA, NOERROR
	binary.BigEndian.PutUint16(out[4:6], 1)
	binary.BigEndian.PutUint16(out[6:8], 0)
	binary.BigEndian.PutUint16(out[8:10], 0)
	binary.BigEndian.PutUint16(out[10:12], 0)
	var rdata []byte
	switch q.qtype {
	case typeA:
		rdata = make([]byte, 4)
	case typeAAAA:
		rdata = make([]byte, 16)
	default:
		return out
	}
	binary.BigEndian.PutUint16(out[6:8], 1)
	out = append(out, 0xC0, 0x0C) // pointer to the question name
	out = binary.BigEndian.AppendUint16(out, q.qtype)
	out = binary.BigEndian.AppendUint16(out, 1) // IN
	out = binary.BigEndian.AppendUint32(out, 60)
	out = binary.BigEndian.AppendUint16(out, uint16(len(rdata)))
	out = append(out, rdata...)
	return out
}

// failReply is SERVFAIL for the query: the app's resolver tries again instead of
// caching a wrong answer.
func failReply(query []byte, q question) []byte {
	out := make([]byte, q.end)
	copy(out, query[:q.end])
	out[2] = 0x80 | (query[2] & 0x79)
	out[3] = 0x80 | 2
	binary.BigEndian.PutUint16(out[4:6], 1)
	binary.BigEndian.PutUint16(out[6:8], 0)
	binary.BigEndian.PutUint16(out[8:10], 0)
	binary.BigEndian.PutUint16(out[10:12], 0)
	return out
}

// truncate keeps header and question and sets TC, for answers too big for one packet.
func truncate(resp []byte, q question) []byte {
	if len(resp) < q.end {
		return resp
	}
	out := make([]byte, q.end)
	copy(out, resp[:q.end])
	out[2] |= 0x02
	binary.BigEndian.PutUint16(out[6:8], 0)
	binary.BigEndian.PutUint16(out[8:10], 0)
	binary.BigEndian.PutUint16(out[10:12], 0)
	return out
}

// minTTL returns the lowest TTL in the answer and authority sections, or -1 when the
// message cannot be read or must not be cached (an error other than NXDOMAIN, or TC).
func minTTL(resp []byte) int {
	if len(resp) < headerLen {
		return -1
	}
	rcode := resp[3] & 0x0F
	if resp[2]&0x02 != 0 || (rcode != 0 && rcode != 3) {
		return -1
	}
	qd := int(binary.BigEndian.Uint16(resp[4:6]))
	an := int(binary.BigEndian.Uint16(resp[6:8]))
	ns := int(binary.BigEndian.Uint16(resp[8:10]))
	off := headerLen
	var err error
	for i := 0; i < qd; i++ {
		if off, err = skipName(resp, off); err != nil {
			return -1
		}
		off += 4
	}
	ttl := -1
	for i := 0; i < an+ns; i++ {
		if off, err = skipName(resp, off); err != nil || off+10 > len(resp) {
			return -1
		}
		t := int(binary.BigEndian.Uint32(resp[off+4 : off+8]))
		if t > 0x7FFFFFFF {
			t = 0
		}
		if ttl < 0 || t < ttl {
			ttl = t
		}
		off += 10 + int(binary.BigEndian.Uint16(resp[off+8:off+10]))
		if off > len(resp) {
			return -1
		}
	}
	if ttl < 0 {
		ttl = 60 // no records (NODATA / NXDOMAIN without SOA)
	}
	return ttl
}

func setID(msg []byte, id uint16) {
	if len(msg) >= 2 {
		binary.BigEndian.PutUint16(msg[0:2], id)
	}
}

// buildQuery makes a plain query; used by Check and the tests.
func buildQuery(id uint16, name string, qtype uint16) []byte {
	out := make([]byte, headerLen, headerLen+len(name)+6)
	binary.BigEndian.PutUint16(out[0:2], id)
	out[2] = 0x01 // RD
	binary.BigEndian.PutUint16(out[4:6], 1)
	for _, l := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		if l == "" {
			continue
		}
		out = append(out, byte(len(l)))
		out = append(out, l...)
	}
	out = append(out, 0)
	out = binary.BigEndian.AppendUint16(out, qtype)
	out = binary.BigEndian.AppendUint16(out, 1)
	return out
}
