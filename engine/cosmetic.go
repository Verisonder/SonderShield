package shield

import (
	_ "embed"
	"sort"
	"strings"
	"sync"
)

// Cosmetic filtering: element hiding ("##selector") and scriptlets ("##+js(...)",
// "#%#//scriptlet(...)", "#$#snippet"), read from AdBlock-style lists and injected into
// pages that HTTPS filtering opens.

//go:embed scriptlets.js
var scriptletLib string

type scriptlet struct {
	name string
	args []string
}

func (s scriptlet) key() string { return s.name + "\x00" + strings.Join(s.args, "\x00") }

type genericExcept struct {
	sel  string
	excl []string // domains (or "name.*" entities) where it doesn't apply
}

type cosmetics struct {
	generic   []string // "##sel" for every site
	genericX  []genericExcept
	genericEx map[string]bool // "#@#sel": dropped from generic
	byDomain  map[string][]string
	unhide    map[string]map[string]bool // domain -> selectors excepted there
	scripts   map[string][]scriptlet     // "" = every site
	noScripts map[string]map[string]bool // domain -> scriptlet keys excepted ("" = all)
	noHide    domainSet                  // $elemhide / $generichide exceptions

	genericCSS string // built once in finish()
	cache      sync.Map
}

func newCosmetics() *cosmetics {
	return &cosmetics{
		genericEx: map[string]bool{},
		byDomain:  map[string][]string{},
		unhide:    map[string]map[string]bool{},
		scripts:   map[string][]scriptlet{},
		noScripts: map[string]map[string]bool{},
		noHide:    domainSet{},
	}
}

// parseCosmetic handles one list line that holds "##", "#@#", "#%#" or "#$#". Returns
// true when it was a cosmetic rule (used or not).
func (c *cosmetics) parseCosmetic(line string) bool {
	for _, sep := range []string{"#@%#", "#@$#", "#@#", "#%#", "#$#", "##"} {
		i := strings.Index(line, sep)
		if i < 0 {
			continue
		}
		if c == nil {
			return true
		}
		domains, body := line[:i], strings.TrimSpace(line[i+len(sep):])
		except := strings.Contains(sep, "@")
		switch sep {
		case "#%#", "#@%#":
			if s, ok := parseAdGuardScriptlet(body); ok {
				c.addScriptlet(domains, s, except)
			}
		case "#$#", "#@$#":
			for _, s := range parseABPSnippets(body) {
				c.addScriptlet(domains, s, except)
			}
		default: // ## or #@#
			if strings.HasPrefix(body, "+js(") && strings.HasSuffix(body, ")") {
				s, ok := parseUBOScriptlet(body[4 : len(body)-1])
				if ok || (except && body == "+js()") {
					c.addScriptlet(domains, s, except)
				}
				return true
			}
			if !plainSelector(body) {
				return true
			}
			c.addHide(domains, body, except)
		}
		return true
	}
	return false
}

func splitDomains(s string) (inc, exc []string) {
	for _, d := range strings.Split(s, ",") {
		d = strings.ToLower(strings.TrimSpace(d))
		if d == "" {
			continue
		}
		if d[0] == '~' {
			exc = append(exc, d[1:])
		} else {
			inc = append(inc, d)
		}
	}
	return
}

func (c *cosmetics) addHide(domains, sel string, except bool) {
	inc, exc := splitDomains(domains)
	if except {
		if len(inc) == 0 {
			c.genericEx[sel] = true
			return
		}
		for _, d := range inc {
			if c.unhide[d] == nil {
				c.unhide[d] = map[string]bool{}
			}
			c.unhide[d][sel] = true
		}
		return
	}
	switch {
	case len(inc) == 0 && len(exc) == 0:
		c.generic = append(c.generic, sel)
	case len(inc) == 0:
		c.genericX = append(c.genericX, genericExcept{sel, exc})
	default:
		for _, d := range inc {
			c.byDomain[d] = append(c.byDomain[d], sel)
		}
		for _, d := range exc { // "a.com,~b.a.com##x": except on the subdomain
			if c.unhide[d] == nil {
				c.unhide[d] = map[string]bool{}
			}
			c.unhide[d][sel] = true
		}
	}
}

func (c *cosmetics) addScriptlet(domains string, s scriptlet, except bool) {
	inc, _ := splitDomains(domains)
	if except {
		k := s.key()
		if s.name == "" {
			k = ""
		}
		if len(inc) == 0 {
			inc = []string{""}
		}
		for _, d := range inc {
			if c.noScripts[d] == nil {
				c.noScripts[d] = map[string]bool{}
			}
			c.noScripts[d][k] = true
		}
		return
	}
	if !knownScriptlet(s.name) {
		return
	}
	if len(inc) == 0 {
		inc = []string{""}
	}
	for _, d := range inc {
		c.scripts[d] = append(c.scripts[d], s)
	}
}

// plainSelector: a CSS selector the browser understands as is. Extended syntax
// (procedural, style, HTML filters) is skipped.
func plainSelector(s string) bool {
	if s == "" || s[0] == '^' || strings.ContainsAny(s, "{}") {
		return false
	}
	for _, x := range []string{":-abp-", ":has-text(", ":xpath(", ":matches-css", ":upward(", ":remove(", ":style(", ":min-text-length(", ":watch-attr(", ":matches-path(", ":matches-attr(", ":matches-prop(", ":others(", ":contains(", ":if(", ":if-not(", ":nth-ancestor(", ":remove-attr(", ":remove-class(", ":-abp-has("} {
		if strings.Contains(s, x) {
			return false
		}
	}
	return true
}

// "name, arg1, arg2" (uBO). Commas inside an argument are escaped as "\,".
func parseUBOScriptlet(inner string) (scriptlet, bool) {
	var parts []string
	var b strings.Builder
	for i := 0; i < len(inner); i++ {
		if inner[i] == '\\' && i+1 < len(inner) && inner[i+1] == ',' {
			b.WriteByte(',')
			i++
			continue
		}
		if inner[i] == ',' {
			parts = append(parts, strings.TrimSpace(b.String()))
			b.Reset()
			continue
		}
		b.WriteByte(inner[i])
	}
	parts = append(parts, strings.TrimSpace(b.String()))
	if parts[0] == "" {
		return scriptlet{}, false
	}
	name := strings.TrimSuffix(parts[0], ".js")
	return scriptlet{name: canonicalScriptlet(name), args: parts[1:]}, true
}

// "//scriptlet('name', 'arg1', "arg2")" (AdGuard).
func parseAdGuardScriptlet(body string) (scriptlet, bool) {
	if !strings.HasPrefix(body, "//scriptlet(") || !strings.HasSuffix(body, ")") {
		return scriptlet{}, false
	}
	in := body[len("//scriptlet(") : len(body)-1]
	var parts []string
	for len(in) > 0 {
		in = strings.TrimLeft(in, " ,")
		if in == "" {
			break
		}
		q := in[0]
		if q != '\'' && q != '"' {
			return scriptlet{}, false
		}
		var b strings.Builder
		j := 1
		for ; j < len(in); j++ {
			if in[j] == '\\' && j+1 < len(in) {
				b.WriteByte(in[j+1])
				j++
				continue
			}
			if in[j] == q {
				break
			}
			b.WriteByte(in[j])
		}
		parts = append(parts, b.String())
		if j >= len(in) {
			break
		}
		in = in[j+1:]
	}
	if len(parts) == 0 {
		return scriptlet{}, false
	}
	return scriptlet{name: canonicalScriptlet(parts[0]), args: parts[1:]}, true
}

// "abort-on-property-read foo; json-prune a.b" (Adblock Plus snippets).
func parseABPSnippets(body string) []scriptlet {
	var out []scriptlet
	for _, part := range strings.Split(body, ";") {
		f := strings.Fields(part)
		if len(f) == 0 {
			continue
		}
		args := f[1:]
		for i, a := range args {
			args[i] = strings.Trim(a, "'\"")
		}
		name := f[0]
		if name == "override-property-read" {
			name = "set-constant"
		}
		out = append(out, scriptlet{name: canonicalScriptlet(name), args: args})
	}
	return out
}

var scriptletAliases = map[string]string{
	"set": "set-constant", "set-constant": "set-constant", "ubo-set-constant": "set-constant", "abp-override-property-read": "set-constant",
	"aopr": "abort-on-property-read", "abort-on-property-read": "abort-on-property-read",
	"aopw": "abort-on-property-write", "abort-on-property-write": "abort-on-property-write",
	"acs": "abort-current-script", "acis": "abort-current-script", "abort-current-script": "abort-current-script", "abort-current-inline-script": "abort-current-script",
	"json-prune": "json-prune",
	"nostif":     "no-settimeout-if", "no-settimeout-if": "no-settimeout-if", "prevent-settimeout": "no-settimeout-if", "settimeout-defuser": "no-settimeout-if",
	"nosiif": "no-setinterval-if", "no-setinterval-if": "no-setinterval-if", "prevent-setinterval": "no-setinterval-if", "setinterval-defuser": "no-setinterval-if",
	"ra": "remove-attr", "remove-attr": "remove-attr",
	"rc": "remove-class", "remove-class": "remove-class",
	"aeld": "prevent-addeventlistener", "addeventlistener-defuser": "prevent-addeventlistener", "prevent-addeventlistener": "prevent-addeventlistener",
	"no-fetch-if": "prevent-fetch", "prevent-fetch": "prevent-fetch",
	"no-xhr-if": "prevent-xhr", "prevent-xhr": "prevent-xhr",
	"nowoif": "prevent-window-open", "no-window-open-if": "prevent-window-open", "prevent-window-open": "prevent-window-open", "window.open-defuser": "prevent-window-open",
}

func canonicalScriptlet(name string) string {
	n := strings.ToLower(strings.TrimSpace(name))
	n = strings.TrimPrefix(strings.TrimPrefix(n, "ubo-"), "abp-")
	n = strings.TrimSuffix(n, ".js")
	if c, ok := scriptletAliases[n]; ok {
		return c
	}
	return n
}

func knownScriptlet(name string) bool {
	_, ok := scriptletAliases[name]
	return ok
}

// finish builds the shared CSS once all lists are read.
func (c *cosmetics) finish() {
	if c == nil {
		return
	}
	var keep []string
	seen := map[string]bool{}
	for _, s := range c.generic {
		if !c.genericEx[s] && !seen[s] {
			seen[s] = true
			keep = append(keep, s)
		}
	}
	sort.Strings(keep)
	c.generic = keep
	c.genericCSS = hideCSS(keep)
}

// hideCSS groups selectors inside :is(), whose forgiving parsing drops a selector the
// browser doesn't know instead of the whole rule.
func hideCSS(sels []string) string {
	var b strings.Builder
	for i := 0; i < len(sels); i += 500 {
		j := i + 500
		if j > len(sels) {
			j = len(sels)
		}
		b.WriteString(":is(")
		b.WriteString(strings.Join(sels[i:j], ","))
		b.WriteString("){display:none!important}\n")
	}
	return b.String()
}

// hostKeys: every suffix of host, plus "name.*" entities ("google.*" for www.google.co.uk).
func hostKeys(host string) []string {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	labels := strings.Split(host, ".")
	var keys []string
	for i := range labels {
		keys = append(keys, strings.Join(labels[i:], "."))
	}
	for i := 0; i < len(labels)-1; i++ {
		for j := i + 1; j < len(labels); j++ {
			keys = append(keys, strings.Join(labels[i:j], ".")+".*")
		}
	}
	return keys
}

type pageExtras struct {
	css, js string
}

// forHost returns the CSS and scriptlet code for one site, cached.
func (c *cosmetics) forHost(host string) pageExtras {
	if c == nil {
		return pageExtras{}
	}
	if v, ok := c.cache.Load(host); ok {
		return v.(pageExtras)
	}
	keys := hostKeys(host)
	unhide := map[string]bool{}
	noScript := map[string]bool{}
	for k := range c.noScripts[""] {
		noScript[k] = true
	}
	for _, k := range keys {
		for s := range c.unhide[k] {
			unhide[s] = true
		}
		for s := range c.noScripts[k] {
			noScript[s] = true
		}
	}
	_, _, noGeneric := c.noHide.matchSrc(strings.ToLower(host))

	var b strings.Builder
	if !noGeneric {
		if len(unhide) == 0 {
			b.WriteString(c.genericCSS)
		} else {
			var g []string
			for _, s := range c.generic {
				if !unhide[s] {
					g = append(g, s)
				}
			}
			b.WriteString(hideCSS(g))
		}
		var gx []string
	gen:
		for _, x := range c.genericX {
			if unhide[x.sel] {
				continue
			}
			for _, e := range x.excl {
				for _, k := range keys {
					if e == k {
						continue gen
					}
				}
			}
			gx = append(gx, x.sel)
		}
		b.WriteString(hideCSS(gx))
	}
	var own []string
	for _, k := range keys {
		for _, s := range c.byDomain[k] {
			if !unhide[s] {
				own = append(own, s)
			}
		}
	}
	b.WriteString(hideCSS(own))

	var calls []string
	seen := map[string]bool{}
	if !noScript[""] {
		for _, k := range append([]string{""}, keys...) {
			for _, s := range c.scripts[k] {
				key := s.key()
				if seen[key] || noScript[key] {
					continue
				}
				seen[key] = true
				calls = append(calls, scriptletCall(s))
			}
		}
	}
	px := pageExtras{css: b.String()}
	if len(calls) > 0 {
		px.js = scriptletLib + strings.Join(calls, "")
		px.js = "(function(){" + px.js + "})();"
	}
	if n := countCache(&c.cache); n < 2000 {
		c.cache.Store(host, px)
	}
	return px
}

func countCache(m *sync.Map) int {
	n := 0
	m.Range(func(_, _ any) bool { n++; return n < 2000 })
	return n
}

func scriptletCall(s scriptlet) string {
	var b strings.Builder
	b.WriteString("try{S[")
	b.WriteString(jsString(s.name))
	b.WriteString("](")
	for i, a := range s.args {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(jsString(a))
	}
	b.WriteString(")}catch(e){}\n")
	return b.String()
}

func jsString(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch {
		case r == '"' || r == '\\':
			b.WriteByte('\\')
			b.WriteRune(r)
		case r == '<':
			b.WriteString(`<`)
		case r < 0x20 || r == 0x2028 || r == 0x2029:
			b.WriteString(`\u`)
			const hex = "0123456789abcdef"
			b.WriteByte(hex[(r>>12)&15])
			b.WriteByte(hex[(r>>8)&15])
			b.WriteByte(hex[(r>>4)&15])
			b.WriteByte(hex[r&15])
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}
