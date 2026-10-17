package shield

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// wgConfig is a parsed wg-quick style config ([Interface] + one or more [Peer]).
type wgConfig struct {
	privateKey string // hex
	addresses  []netip.Addr
	mtu        int
	peers      []wgPeer
}

type wgPeer struct {
	publicKey    string // hex
	presharedKey string // hex, optional
	endpoint     string // host:port as written
	allowedIPs   []string
	keepalive    int
}

func keyHex(b64 string) (string, error) {
	k, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(k) != 32 {
		return "", errors.New("bad key")
	}
	return hex.EncodeToString(k), nil
}

func parseWireGuard(text string) (*wgConfig, error) {
	c := &wgConfig{mtu: 1420}
	section := ""
	var peer *wgPeer
	sc := bufio.NewScanner(strings.NewReader(text))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			if section == "peer" {
				c.peers = append(c.peers, wgPeer{})
				peer = &c.peers[len(c.peers)-1]
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k, v = strings.ToLower(strings.TrimSpace(k)), strings.TrimSpace(v)
		var err error
		switch section {
		case "interface":
			switch k {
			case "privatekey":
				c.privateKey, err = keyHex(v)
			case "address":
				for _, a := range strings.Split(v, ",") {
					p, perr := netip.ParsePrefix(strings.TrimSpace(a))
					if perr != nil {
						addr, aerr := netip.ParseAddr(strings.TrimSpace(a))
						if aerr != nil {
							return nil, fmt.Errorf("Address %q", a)
						}
						c.addresses = append(c.addresses, addr)
						continue
					}
					c.addresses = append(c.addresses, p.Addr())
				}
			case "mtu":
				c.mtu, err = strconv.Atoi(v)
			}
		case "peer":
			switch k {
			case "publickey":
				peer.publicKey, err = keyHex(v)
			case "presharedkey":
				peer.presharedKey, err = keyHex(v)
			case "endpoint":
				peer.endpoint = v
			case "allowedips":
				for _, a := range strings.Split(v, ",") {
					peer.allowedIPs = append(peer.allowedIPs, strings.TrimSpace(a))
				}
			case "persistentkeepalive":
				peer.keepalive, err = strconv.Atoi(v)
			}
		}
		if err != nil {
			return nil, fmt.Errorf("%s: %v", k, err)
		}
	}
	switch {
	case c.privateKey == "":
		return nil, errors.New("no PrivateKey in [Interface]")
	case len(c.addresses) == 0:
		return nil, errors.New("no Address in [Interface]")
	case len(c.peers) == 0:
		return nil, errors.New("no [Peer]")
	}
	for _, p := range c.peers {
		if p.publicKey == "" || p.endpoint == "" {
			return nil, errors.New("a [Peer] needs PublicKey and Endpoint")
		}
	}
	return c, nil
}

// ipc turns the config into wireguard-go's UAPI text, resolving endpoint host names.
func (c *wgConfig) ipc() (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", c.privateKey)
	for _, p := range c.peers {
		host, port, err := net.SplitHostPort(p.endpoint)
		if err != nil {
			return "", fmt.Errorf("Endpoint %q", p.endpoint)
		}
		ip := net.ParseIP(host)
		if ip == nil {
			ips, err := net.LookupIP(host)
			if err != nil || len(ips) == 0 {
				return "", fmt.Errorf("can't resolve %s", host)
			}
			ip = ips[0]
		}
		fmt.Fprintf(&b, "public_key=%s\n", p.publicKey)
		if p.presharedKey != "" {
			fmt.Fprintf(&b, "preshared_key=%s\n", p.presharedKey)
		}
		fmt.Fprintf(&b, "endpoint=%s\n", net.JoinHostPort(ip.String(), port))
		if p.keepalive > 0 {
			fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.keepalive)
		}
		if len(p.allowedIPs) == 0 {
			b.WriteString("allowed_ip=0.0.0.0/0\nallowed_ip=::/0\n")
		}
		for _, a := range p.allowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String(), nil
}

// CheckWireGuard validates a config without using it. Returns "" when fine, else the problem.
func CheckWireGuard(text string) string {
	if _, err := parseWireGuard(text); err != nil {
		return err.Error()
	}
	return ""
}

// SetWireGuard makes full-tunnel connections go out through this WireGuard config (wg-quick
// format). Empty text: back to the direct network. Takes effect on the next StartFull.
func (e *Engine) SetWireGuard(text string) error {
	if strings.TrimSpace(text) == "" {
		e.wg.Store(nil)
		return nil
	}
	c, err := parseWireGuard(text)
	if err != nil {
		return err
	}
	e.wg.Store(c)
	return nil
}
