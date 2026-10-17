package shield

import (
	"bufio"
	"io"
	"os"
	"strings"
)

// domainSet matches a name and every name under it: "ads.example.com" in the set
// blocks "x.ads.example.com" too. A map keyed by the full name, walked label by label
// from the left, costs one lookup per label and stays small enough for big lists.
// The value is the index of the list the entry came from (0 for your own rules).
type domainSet map[string]uint16

func (s domainSet) match(name string) (string, bool) {
	m, _, ok := s.matchSrc(name)
	return m, ok
}

func (s domainSet) matchSrc(name string) (string, uint16, bool) {
	for {
		if src, ok := s[name]; ok {
			return name, src, true
		}
		i := strings.IndexByte(name, '.')
		if i < 0 {
			return "", 0, false
		}
		name = name[i+1:]
	}
}

// filter is one immutable snapshot; the engine swaps whole snapshots on reload.
type filter struct {
	listBlock domainSet
	listAllow domainSet // @@ rules inside lists
	userBlock domainSet
	userAllow domainSet
	listNames []string // list ids by index, for reasons like "list:stevenblack"
}

// verdicts
const (
	verdictAllowed = 0
	verdictBlocked = 1
	verdictFailed  = 2
)

// reasons, also shown in the app
const (
	reasonNone      = ""
	reasonUserAllow = "your allow rule"
	reasonUserBlock = "your block rule"
	reasonListAllow = "list exception"
	reasonList      = "block list" // with list names: "list:<id>"
)

// decide: your allow rules win, then your block rules, then list exceptions, then lists.
func (f *filter) decide(name string) (bool, string, string) {
	if f == nil {
		return false, reasonNone, ""
	}
	if m, ok := f.userAllow.match(name); ok {
		return false, reasonUserAllow, m
	}
	if m, ok := f.userBlock.match(name); ok {
		return true, reasonUserBlock, m
	}
	if m, ok := f.listAllow.match(name); ok {
		return false, reasonListAllow, m
	}
	if m, src, ok := f.listBlock.matchSrc(name); ok {
		if int(src) < len(f.listNames) && f.listNames[src] != "" {
			return true, "list:" + f.listNames[src], m
		}
		return true, reasonList, m
	}
	return false, reasonNone, ""
}

// parseList reads hosts files ("0.0.0.0 host"), AdBlock-style DNS rules
// ("||host^", "@@||host^") and plain domain lists ("host", "*.host"), and adds to
// block and allow. Rules that can't be expressed per domain are skipped.
func parseList(r io.Reader, block, allow domainSet, src uint16, cos *cosmetics) (int, error) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	n := 0
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] == '!' || line[0] == '[' || (line[0] == '#' && !genericCosmetic(line)) {
			continue
		}
		if i := strings.IndexByte(line, '#'); i >= 0 {
			if strings.Contains(line, "##") || strings.Contains(line, "#@#") || strings.Contains(line, "#$#") || strings.Contains(line, "#%#") || strings.Contains(line, "#@$#") || strings.Contains(line, "#@%#") {
				cos.parseCosmetic(line) // page rule for HTTPS filtering, not DNS
				continue
			}
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		target, isAllow := block, false
		if strings.HasPrefix(line, "@@") {
			target, isAllow = allow, true
			line = line[2:]
		}
		if strings.HasPrefix(line, "||") {
			line = line[2:]
			if i := strings.IndexByte(line, '$'); i >= 0 {
				if isAllow && hideException(line[i+1:]) {
					if d, ok := cleanDomain(strings.TrimSuffix(strings.TrimSuffix(line[:i], "|"), "^")); ok && cos != nil {
						cos.noHide[d] = src
					}
					continue
				}
				if !okModifiers(line[i+1:]) {
					continue
				}
				line = line[:i]
			}
			if !strings.HasSuffix(line, "^") && !strings.HasSuffix(line, "^|") {
				continue // path rule
			}
			line = strings.TrimSuffix(strings.TrimSuffix(line, "|"), "^")
			if d, ok := cleanDomain(line); ok {
				target[d] = src
				n++
			}
			continue
		}
		if isAllow { // "@@" without "||" is not a DNS rule
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 && isIP(fields[0]) {
			for _, h := range fields[1:] {
				if d, ok := cleanDomain(h); ok && !localName(d) {
					block[d] = src
					n++
				}
			}
			continue
		}
		if len(fields) == 1 {
			h := strings.TrimPrefix(fields[0], "*.")
			if d, ok := cleanDomain(h); ok {
				block[d] = src
				n++
			}
		}
	}
	return n, sc.Err()
}

// parseRules reads the user's own rules: one domain per line, "*." allowed, "#" comments.
func parseRules(text string) domainSet {
	s := domainSet{}
	for _, line := range strings.Split(text, "\n") {
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		line = strings.TrimSpace(line)
		line = strings.TrimPrefix(line, "||")
		line = strings.TrimSuffix(line, "^")
		line = strings.TrimPrefix(line, "*.")
		if d, ok := cleanDomain(line); ok {
			s[d] = 0
		}
	}
	return s
}

// genericCosmetic: a page rule for every site ("##.ad"), not a "#" comment.
func genericCosmetic(line string) bool {
	for _, p := range []string{"##", "#@#", "#%#", "#$#", "#@%#", "#@$#"} {
		if strings.HasPrefix(line, p) {
			return true
		}
	}
	return false
}

// hideException: "$elemhide", "$generichide" and friends: no element hiding on that site.
func hideException(m string) bool {
	for _, p := range strings.Split(m, ",") {
		switch strings.TrimSpace(p) {
		case "elemhide", "ehide", "generichide", "ghide", "document", "doc":
			return true
		}
	}
	return false
}

func loadListFile(path string, block, allow domainSet, src uint16, cos *cosmetics) (int, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	return parseList(f, block, allow, src, cos)
}

func okModifiers(m string) bool {
	for _, p := range strings.Split(m, ",") {
		switch strings.TrimSpace(p) {
		case "important", "all", "":
		default:
			return false
		}
	}
	return true
}

func cleanDomain(s string) (string, bool) {
	s = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(s), "."))
	if len(s) < 3 || len(s) > 253 || !strings.Contains(s, ".") {
		return "", false
	}
	if s[0] == '.' || s[0] == '-' {
		return "", false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_') {
			return "", false
		}
	}
	if strings.Contains(s, "..") || isIP(s) {
		return "", false
	}
	return s, true
}

func isIP(s string) bool {
	if strings.Contains(s, ":") {
		return true
	}
	dots := 0
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == '.' {
			dots++
		} else if c < '0' || c > '9' {
			return false
		}
	}
	return dots == 3
}

func localName(d string) bool {
	switch d {
	case "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
		"ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0":
		return true
	}
	return false
}
