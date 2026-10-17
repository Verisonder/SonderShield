package main

import (
	"context"
	"errors"
	"time"
)

// start turns protection on: the engine serves DNS on the loopback, the computer's DNS is
// pointed at it, the firewall rules go in, and missing lists download. Safe to call twice.
func (a *App) start() error {
	a.mu.Lock()
	if a.on {
		a.mu.Unlock()
		return nil
	}
	a.problem = ""
	a.mu.Unlock()

	a.applyConfig()
	if err := a.eng.StartLocal(dnsPort); err != nil {
		a.setProblem("Could not start the DNS server. Is another DNS program (like Pi-hole or Acrylic) already using port 53? " + err.Error())
		return err
	}
	a.eng.SetOutsideInterface(a.plat.outsideInterfaces())
	if err := a.plat.setSystemDNS("127.0.0.1"); err != nil {
		a.eng.Stop()
		a.setProblem("Could not point Windows at SonderShield: " + err.Error() + " (SonderShield must run as administrator.)")
		return err
	}

	a.mu.Lock()
	a.on = true
	a.startedAt = time.Now().UnixMilli()
	a.cfg.On = true
	cfg := a.cfg
	a.ctx, a.cancel = context.WithCancel(context.Background())
	a.mu.Unlock()
	a.st.save("config.json", &cfg)

	a.wg.Add(1)
	go a.background(a.ctx)
	return nil
}

// stop turns protection off and puts the computer's DNS back.
func (a *App) stop() {
	a.mu.Lock()
	if !a.on {
		a.mu.Unlock()
		return
	}
	a.on = false
	a.startedAt = 0
	a.cfg.On = false
	cfg := a.cfg
	cancel := a.cancel
	a.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	a.plat.restoreSystemDNS()
	a.plat.clearFirewall()
	a.eng.Stop()
	a.wg.Wait()
	a.st.save("config.json", &cfg)
	a.saveStats()
}

func (a *App) setProblem(msg string) {
	a.mu.Lock()
	a.problem = msg
	a.mu.Unlock()
}

// background downloads missing lists, saves stats, and refreshes lists on a schedule.
func (a *App) background(ctx context.Context) {
	defer a.wg.Done()

	// First, fetch any enabled list we don't have yet, then reload.
	go func() {
		c := a.config()
		changed := false
		for _, l := range c.allLists() {
			if contains(c.Lists, l.ID) && !a.lists.has(l.ID) {
				if ok, _ := a.lists.update(ctx, l, false); ok {
					changed = true
				}
			}
			if ctx.Err() != nil {
				return
			}
		}
		if changed {
			a.applyConfig()
		}
	}()

	save := time.NewTicker(10 * time.Second)
	defer save.Stop()
	update := time.NewTicker(1 * time.Hour)
	defer update.Stop()
	lastUpdate := time.Now()
	for {
		select {
		case <-ctx.Done():
			return
		case <-save.C:
			a.saveStats()
		case <-update.C:
			c := a.config()
			if c.UpdateHours > 0 && time.Since(lastUpdate) >= time.Duration(c.UpdateHours)*time.Hour {
				lastUpdate = time.Now()
				a.updateAll(ctx, false)
			}
		}
	}
}

func (a *App) saveStats() {
	if cp, ok := a.stats.snapshot(time.Now()); ok {
		a.st.save("stats.json", cp)
	}
}

// updateAll refreshes every enabled list. Returns how many changed.
func (a *App) updateAll(ctx context.Context, force bool) int {
	c := a.config()
	n := 0
	for _, l := range c.allLists() {
		if !contains(c.Lists, l.ID) {
			continue
		}
		if ok, _ := a.lists.update(ctx, l, force); ok {
			n++
		}
		if ctx.Err() != nil {
			break
		}
	}
	if n > 0 && a.running() {
		a.applyConfig()
	}
	return n
}

// active reports whether the computer's DNS is really reaching us: a query seen in the
// last 30 seconds, or protection just started (give it a moment).
func (a *App) active() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.on {
		return false
	}
	now := time.Now().UnixMilli()
	return now-a.lastQuery < 30_000 || now-a.startedAt < 15_000
}

var errNotRunning = errors.New("not running")
