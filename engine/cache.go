package shield

import (
	"sync"
	"time"
)

// cache keeps upstream answers until their TTL runs out (capped at an hour).
// Keyed by the question bytes after the ID, so case or flag differences miss, which
// is harmless.
type cache struct {
	mu  sync.Mutex
	m   map[string]cacheEntry
	max int
}

type cacheEntry struct {
	resp    []byte
	expires time.Time
}

func newCache(max int) *cache { return &cache{m: make(map[string]cacheEntry), max: max} }

func cacheKey(query []byte, q question) string {
	// flags byte 2 bit 0 (RD) and the question; skip the ID.
	return string(query[headerLen:q.end])
}

func (c *cache) get(key string, now time.Time) []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[key]
	if !ok {
		return nil
	}
	if now.After(e.expires) {
		delete(c.m, key)
		return nil
	}
	return e.resp
}

func (c *cache) put(key string, resp []byte, ttl int, now time.Time) {
	if ttl <= 0 {
		return
	}
	if ttl > 3600 {
		ttl = 3600
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.m) >= c.max {
		// Drop expired entries first, then anything, until a quarter is free.
		for k, e := range c.m {
			if now.After(e.expires) {
				delete(c.m, k)
			}
		}
		for k := range c.m {
			if len(c.m) < c.max*3/4 {
				break
			}
			delete(c.m, k)
		}
	}
	c.m[key] = cacheEntry{resp: resp, expires: now.Add(time.Duration(ttl) * time.Second)}
}

func (c *cache) clear() {
	c.mu.Lock()
	c.m = make(map[string]cacheEntry)
	c.mu.Unlock()
}
