package main

import (
	"sort"
	"sync"
	"time"
)

const statHours = 168

// stats: all-time totals, hourly buckets for the chart, per-domain block counts, today.
type stats struct {
	mu      sync.Mutex
	Total   int64            `json:"total"`
	Blocked int64            `json:"blocked"`
	Threats int64            `json:"threats"`
	Hours   map[int64][2]int `json:"hours"`
	Top     map[string]int   `json:"top"`
	Day     string           `json:"day"`
	Today   [2]int64         `json:"today"`
	dirty   bool
}

func newStats() *stats { return &stats{Hours: map[int64][2]int{}, Top: map[string]int{}} }

func (s *stats) record(domain string, blocked, threat bool, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.Hours == nil {
		s.Hours, s.Top = map[int64][2]int{}, map[string]int{}
	}
	if day := now.Format("2006-01-02"); day != s.Day {
		s.Day, s.Today = day, [2]int64{}
	}
	s.Total++
	s.Today[0]++
	h := now.Unix() / 3600
	v := s.Hours[h]
	v[0]++
	if blocked {
		s.Blocked++
		s.Today[1]++
		v[1]++
		if threat {
			s.Threats++
		}
		s.Top[domain]++
		if len(s.Top) > 2000 {
			s.trimTop(1000)
		}
	}
	s.Hours[h] = v
	s.dirty = true
}

func (s *stats) trimTop(keep int) {
	type kv struct {
		k string
		v int
	}
	all := make([]kv, 0, len(s.Top))
	for k, v := range s.Top {
		all = append(all, kv{k, v})
	}
	sort.Slice(all, func(i, j int) bool { return all[i].v > all[j].v })
	s.Top = map[string]int{}
	for i := 0; i < keep && i < len(all); i++ {
		s.Top[all[i].k] = all[i].v
	}
}

type statsView struct {
	Total   int64        `json:"total"`
	Blocked int64        `json:"blocked"`
	Threats int64        `json:"threats"`
	Today   [2]int64     `json:"today"`
	Hours   [][2]int     `json:"hours"`
	Top     []domainHits `json:"top"`
}

type domainHits struct {
	Domain string `json:"domain"`
	Hits   int    `json:"hits"`
}

func (s *stats) view(now time.Time) statsView {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := statsView{Total: s.Total, Blocked: s.Blocked, Threats: s.Threats, Top: []domainHits{}}
	if s.Day == now.Format("2006-01-02") {
		v.Today = s.Today
	}
	nowH := now.Unix() / 3600
	for i := int64(statHours - 1); i >= 0; i-- {
		v.Hours = append(v.Hours, s.Hours[nowH-i])
	}
	for d, n := range s.Top {
		v.Top = append(v.Top, domainHits{d, n})
	}
	sort.Slice(v.Top, func(i, j int) bool {
		if v.Top[i].Hits != v.Top[j].Hits {
			return v.Top[i].Hits > v.Top[j].Hits
		}
		return v.Top[i].Domain < v.Top[j].Domain
	})
	if len(v.Top) > 10 {
		v.Top = v.Top[:10]
	}
	return v
}

func (s *stats) snapshot(now time.Time) (*stats, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.dirty {
		return nil, false
	}
	s.dirty = false
	min := now.Unix()/3600 - statHours
	cp := &stats{Total: s.Total, Blocked: s.Blocked, Threats: s.Threats, Day: s.Day, Today: s.Today, Hours: map[int64][2]int{}, Top: map[string]int{}}
	for k, v := range s.Hours {
		if k > min {
			cp.Hours[k] = v
		} else {
			delete(s.Hours, k)
		}
	}
	for k, v := range s.Top {
		cp.Top[k] = v
	}
	return cp, true
}

func (s *stats) reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Total, s.Blocked, s.Threats, s.Today = 0, 0, 0, [2]int64{}
	s.Hours, s.Top = map[int64][2]int{}, map[string]int{}
	s.dirty = true
}
