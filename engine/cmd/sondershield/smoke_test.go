//go:build !windows

package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"testing"
	"time"
)

func TestBackend(t *testing.T) {
	dnsPort = 15353 // high port: no root needed here
	dir := t.TempDir()
	plat := &otherPlatform{dir: dir}
	a := newApp(plat)
	// A tiny local list so the engine has something to load.
	os.WriteFile(dir+"/stevenblack.txt", []byte("0.0.0.0 ads.example.com\n"), 0o644)
	a.lists.set("stevenblack", listMeta{Entries: 1, Updated: time.Now().UnixMilli()})
	a.setConfig(Config{Lists: []string{"stevenblack"}, Upstream: "cf-udp"})

	srv, err := newServer(a)
	if err != nil {
		t.Fatal(err)
	}
	go srv.serve()
	base := "http://" + srv.ln.Addr().String()
	tok := srv.token

	get := func(path string) []byte {
		req, _ := http.NewRequest("GET", base+path, nil)
		req.Header.Set("X-Token", tok)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 && resp.StatusCode != 204 {
			t.Fatalf("%s -> %d", path, resp.StatusCode)
		}
		b, _ := io.ReadAll(resp.Body)
		return b
	}

	// No token -> forbidden.
	resp, _ := http.Get(base + "/api/state")
	if resp.StatusCode != 403 {
		t.Fatalf("missing token should be 403, got %d", resp.StatusCode)
	}

	// Start protection.
	get("/api/toggle")
	var st stateView
	json.Unmarshal(get("/api/state"), &st)
	if !st.On {
		t.Fatal("not on after toggle")
	}
	if st.Domains < 1 {
		t.Fatalf("expected a loaded domain, got %d", st.Domains)
	}

	// A blocked lookup flows through the engine and into the log + stats.
	if r := a.eng.Resolve(buildTestQuery("ads.example.com")); len(r) == 0 {
		t.Fatal("no DNS reply")
	}
	time.Sleep(50 * time.Millisecond)
	var logs []logEntry
	json.Unmarshal(get("/api/log?after=0"), &logs)
	if len(logs) == 0 || logs[0].Domain != "ads.example.com" || logs[0].Verdict != 1 {
		t.Fatalf("log: %+v", logs)
	}
	json.Unmarshal(get("/api/state"), &st)
	if st.Stats.Blocked < 1 {
		t.Fatal("block not counted in stats")
	}

	// Lists endpoint shows it enabled.
	var lv []listView
	json.Unmarshal(get("/api/lists"), &lv)
	found := false
	for _, l := range lv {
		if l.ID == "stevenblack" && l.On {
			found = true
		}
	}
	if !found {
		t.Fatal("stevenblack not listed as on")
	}

	get("/api/toggle") // stop
	json.Unmarshal(get("/api/state"), &st)
	if st.On {
		t.Fatal("still on after second toggle")
	}
}

// buildTestQuery makes a minimal DNS A-record query for name.
func buildTestQuery(name string) []byte {
	q := []byte{0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0} // id, RD, 1 question
	for _, label := range splitDots(name) {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	q = append(q, 0, 0, 1, 0, 1) // root, QTYPE=A, QCLASS=IN
	return q
}

func splitDots(s string) []string {
	var out []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '.' {
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return append(out, s[start:])
}
