package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	shield "github.com/Verisonder/SonderShield/engine"
)

type listMeta struct {
	Entries      int    `json:"entries"`
	Updated      int64  `json:"updated"` // ms
	ETag         string `json:"etag"`
	LastModified string `json:"lastModified"`
	Error        string `json:"error"`
}

type listStore struct {
	dir    string
	st     *store
	client *http.Client
	mu     sync.Mutex
	meta   map[string]listMeta
}

func newListStore(dir string, st *store) *listStore {
	os.MkdirAll(dir, 0o755)
	ls := &listStore{dir: dir, st: st, meta: map[string]listMeta{}}
	st.load("lists.json", &ls.meta)
	ls.client = &http.Client{Timeout: 3 * time.Minute}
	return ls
}

func (ls *listStore) file(id string) string { return filepath.Join(ls.dir, id+".txt") }

func (ls *listStore) has(id string) bool {
	fi, err := os.Stat(ls.file(id))
	return err == nil && fi.Size() > 0
}

func (ls *listStore) get(id string) listMeta {
	ls.mu.Lock()
	defer ls.mu.Unlock()
	return ls.meta[id]
}

func (ls *listStore) set(id string, m listMeta) {
	ls.mu.Lock()
	ls.meta[id] = m
	cp := make(map[string]listMeta, len(ls.meta))
	for k, v := range ls.meta {
		cp[k] = v
	}
	ls.mu.Unlock()
	ls.st.save("lists.json", cp)
}

func (ls *listStore) remove(id string) {
	os.Remove(ls.file(id))
	ls.mu.Lock()
	delete(ls.meta, id)
	ls.mu.Unlock()
}

// update downloads one list; skipped when the server says it didn't change, unless force.
func (ls *listStore) update(ctx context.Context, l listDef, force bool) (bool, error) {
	m := ls.get(l.ID)
	req, err := http.NewRequestWithContext(ctx, "GET", l.URL, nil)
	if err != nil {
		return false, err
	}
	req.Header.Set("User-Agent", "SonderShield")
	if !force && ls.has(l.ID) {
		if m.ETag != "" {
			req.Header.Set("If-None-Match", m.ETag)
		}
		if m.LastModified != "" {
			req.Header.Set("If-Modified-Since", m.LastModified)
		}
	}
	fail := func(e error) (bool, error) {
		m.Error = e.Error()
		ls.set(l.ID, m)
		return false, e
	}
	resp, err := ls.client.Do(req)
	if err != nil {
		return fail(errors.New("could not download"))
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		m.Updated, m.Error = time.Now().UnixMilli(), ""
		ls.set(l.ID, m)
		return false, nil
	}
	if resp.StatusCode != 200 {
		return fail(fmt.Errorf("HTTP %d", resp.StatusCode))
	}
	tmp := ls.file(l.ID) + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return false, err
	}
	_, err = io.Copy(f, io.LimitReader(resp.Body, 200<<20))
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return fail(errors.New("download cut off"))
	}
	n, err := shield.CountList(tmp)
	if err != nil || n == 0 {
		os.Remove(tmp)
		return fail(errors.New("no usable entries"))
	}
	os.Remove(ls.file(l.ID))
	if err := os.Rename(tmp, ls.file(l.ID)); err != nil {
		return false, err
	}
	ls.set(l.ID, listMeta{Entries: n, Updated: time.Now().UnixMilli(), ETag: resp.Header.Get("ETag"), LastModified: resp.Header.Get("Last-Modified")})
	return true, nil
}
