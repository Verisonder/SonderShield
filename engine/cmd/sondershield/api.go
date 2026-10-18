package main

import (
	"context"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"io/fs"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

//go:embed ui
var uiFS embed.FS

// server is the local web server the window talks to. It listens on 127.0.0.1 only, and
// every API call must carry a token made fresh at start, so nothing off this computer can
// reach it.
type server struct {
	a      *App
	token  string
	ln     net.Listener
	onQuit func()
	onShow func()
}

func newServer(a *App) (*server, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	var b [16]byte
	rand.Read(b[:])
	return &server{a: a, token: hex.EncodeToString(b[:]), ln: ln}, nil
}

func (s *server) url() string {
	return "http://" + s.ln.Addr().String() + "/?t=" + s.token
}

func (s *server) serve() error {
	sub, _ := fs.Sub(uiFS, "ui")
	mux := http.NewServeMux()
	mux.Handle("/", http.FileServer(http.FS(sub)))
	mux.HandleFunc("/api/", s.auth(s.api))
	return http.Serve(s.ln, mux)
}

// auth checks the token (from the ?t= query or the X-Token header) on every API call.
func (s *server) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		t := r.Header.Get("X-Token")
		if t == "" {
			t = r.URL.Query().Get("t")
		}
		if t != s.token {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		h(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func (s *server) api(w http.ResponseWriter, r *http.Request) {
	a := s.a
	switch strings.TrimPrefix(r.URL.Path, "/api/") {
	case "state":
		writeJSON(w, s.state())
	case "toggle":
		if a.running() {
			a.stop()
		} else {
			a.start()
		}
		writeJSON(w, s.state())
	case "config":
		if r.Method == "POST" {
			var c Config
			if json.NewDecoder(r.Body).Decode(&c) == nil {
				a.setConfig(c)
			}
		}
		writeJSON(w, a.config())
	case "lists":
		writeJSON(w, s.lists())
	case "profiles":
		writeJSON(w, s.profiles())
	case "lists/update":
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
		defer cancel()
		n := a.updateAll(ctx, true)
		writeJSON(w, map[string]int{"changed": n})
	case "log":
		after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
		writeJSON(w, s.logSince(after))
	case "clear-log":
		a.logMu.Lock()
		a.log = nil
		a.logMu.Unlock()
		w.WriteHeader(204)
	case "reset-stats":
		a.stats.reset()
		a.saveStats()
		w.WriteHeader(204)
	case "autostart":
		if r.Method == "POST" {
			exe, _ := osExecutable()
			on := r.URL.Query().Get("on") == "1"
			a.plat.setAutostart(on, exe)
		}
		writeJSON(w, map[string]bool{"on": a.plat.autostartOn()})
	case "show":
		w.WriteHeader(204)
		if s.onShow != nil {
			s.onShow()
		}
	case "quit":
		w.WriteHeader(204)
		if s.onQuit != nil {
			go s.onQuit()
		}
	case "programs":
		writeJSON(w, s.a.plat.listPrograms())
	case "connections":
		writeJSON(w, s.a.connections())
	case "history":
		writeJSON(w, s.a.hist.list())
	case "timeline":
		writeJSON(w, s.a.tl.list())
	case "icon":
		png := s.a.icon(r.URL.Query().Get("p"))
		if png == nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "max-age=86400")
		w.Write(png)
	case "diag":
		writeJSON(w, s.a.diag())
	case "killswitch":
		on := r.URL.Query().Get("on") == "1"
		if err := s.a.plat.setKillSwitch(on); err != nil {
			w.WriteHeader(500)
			writeJSON(w, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, map[string]bool{"kill": on})
	case "catalog":
		writeJSON(w, map[string]any{"upstreams": upstreams, "version": version})
	case "update/check":
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
		defer cancel()
		info, err := checkUpdate(ctx)
		if err != nil {
			writeJSON(w, map[string]any{"current": version, "error": err.Error()})
			return
		}
		writeJSON(w, info)
	case "update/apply":
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
		err := applyUpdate(ctx, func() {
			if s.a.running() {
				s.a.stop()
			}
			removeInstance(s.a.plat.dataDir())
		})
		cancel()
		if err != nil {
			w.WriteHeader(500)
			writeJSON(w, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, map[string]bool{"updating": true}) // the process is now relaunching
	default:
		http.NotFound(w, r)
	}
}

type stateView struct {
	On         bool      `json:"on"`
	Active     bool      `json:"active"`
	Problem    string    `json:"problem"`
	Domains    int       `json:"domains"`
	StartedAt  int64     `json:"startedAt"`
	Stats      statsView `json:"stats"`
	Today      [2]int64  `json:"today"`
	Config     Config    `json:"config"`
	Mode       string    `json:"mode"`
	Kill       bool      `json:"kill"`
	DataIn     uint64    `json:"dataIn"`
	DataOut    uint64    `json:"dataOut"`
	Conns      int       `json:"conns"`
	Apps       int       `json:"apps"`
	ProfileNow string    `json:"profileNow"` // profile in use now ("" = own picks)
	Scheduled  bool      `json:"scheduled"`  // ...because a schedule is running
}

func (s *server) state() stateView {
	a := s.a
	a.mu.Lock()
	on, started, problem, mode := a.on, a.startedAt, a.problem, a.mode
	cfg := a.cfg
	a.mu.Unlock()
	sv := a.stats.view(time.Now())
	out := stateView{
		On: on, Active: a.active(), Problem: problem, Domains: int(a.eng.DomainCount()),
		StartedAt: started, Stats: sv, Today: sv.Today, Config: cfg, Mode: mode,
		Kill: a.plat.killSwitchOn(),
	}
	out.ProfileNow, out.Scheduled = cfg.activeProfile(time.Now())
	if on {
		a.data.sample(a.plat.rawNetCounters())
		din, dout := a.data.totals()
		conns, apps := a.plat.connCounts()
		out.DataIn, out.DataOut, out.Conns, out.Apps = din, dout, conns, apps
	}
	return out
}

type listView struct {
	listDef
	On   bool     `json:"on"`
	Meta listMeta `json:"meta"`
}

func (s *server) lists() []listView {
	a := s.a
	c := a.config()
	var out []listView
	on := a.listsNow(c)
	for _, l := range c.allLists() {
		out = append(out, listView{listDef: l, On: contains(on, l.ID), Meta: a.lists.get(l.ID)})
	}
	return out
}

type profileView struct {
	Profile
	Lists  []string `json:"lists"`  // with the regional lists added
	Active bool     `json:"active"` // in use now
}

// profiles lists every profile with the lists it turns on here, and which is in use.
func (s *server) profiles() map[string]any {
	a := s.a
	c := a.config()
	now, sched := c.activeProfile(time.Now())
	var out []profileView
	for _, p := range c.allProfiles() {
		pc := Config{Profile: p.ID, Profiles: c.Profiles}
		out = append(out, profileView{Profile: p, Lists: pc.activeLists(time.Now(), a.lang, a.country), Active: p.ID == now})
	}
	return map[string]any{"profiles": out, "chosen": c.Profile, "now": now, "scheduled": sched, "schedules": c.Schedules}
}

func (s *server) logSince(after int64) []logEntry {
	a := s.a
	a.logMu.Lock()
	defer a.logMu.Unlock()
	var out []logEntry
	for i := len(a.log) - 1; i >= 0; i-- {
		if a.log[i].Seq <= after {
			break
		}
		out = append(out, a.log[i])
	}
	return out
}

func osExecutable() (string, error) { return execPath() }
