package shield

import (
	"sync"
	"time"
)

type uidKey struct {
	proto int
	src   string
	port  int
}

type uidEntry struct {
	uid int
	at  time.Time
}

// uidCache remembers which app owns a source address and port for a minute: a socket keeps
// its port, so repeated queries and packets don't each cost a call into the app.
type uidCache struct {
	mu sync.Mutex
	m  map[uidKey]uidEntry
}

func (c *uidCache) get(k uidKey) (int, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[k]
	if !ok || time.Since(e.at) > time.Minute {
		return 0, false
	}
	return e.uid, true
}

func (c *uidCache) put(k uidKey, uid int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil || len(c.m) > 4096 {
		c.m = make(map[uidKey]uidEntry)
	}
	c.m[k] = uidEntry{uid, time.Now()}
}
