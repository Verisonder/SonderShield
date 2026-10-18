package main

import (
	"testing"
	"time"
)

func at(day time.Weekday, h, m int) time.Time {
	// 2026-10-18 is a Sunday.
	return time.Date(2026, 10, 18+int(day), h, m, 0, 0, time.Local)
}

func TestProfileListsExist(t *testing.T) {
	ids := map[string]bool{}
	for _, l := range catalog {
		ids[l.ID] = true
	}
	for _, p := range builtinProfiles {
		for _, l := range p.Lists {
			if !ids[l] {
				t.Errorf("profile %s uses unknown list %s", p.ID, l)
			}
		}
	}
}

func TestDefaultProfileMatchesDefaults(t *testing.T) {
	c := defaultConfig("en", "MA")
	got := c.activeLists(time.Now(), "en", "MA")
	want := defaultLists("en", "MA")
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for _, w := range want {
		if !contains(got, w) {
			t.Fatalf("missing %s in %v", w, got)
		}
	}
}

func TestOwnPicksWhenNoProfile(t *testing.T) {
	c := Config{Lists: []string{"easylist"}}
	if l := c.activeLists(time.Now(), "en", "US"); len(l) != 1 || l[0] != "easylist" {
		t.Fatal(l)
	}
	c.Profile = "gone" // deleted profile falls back to own picks
	if id, _ := c.activeProfile(time.Now()); id != "" {
		t.Fatal(id)
	}
}

func TestGamingHasNoRegional(t *testing.T) {
	c := Config{Profile: "gaming"}
	if l := c.activeLists(time.Now(), "fr", "MA"); contains(l, "liste-fr") {
		t.Fatal(l)
	}
}

func TestSchedule(t *testing.T) {
	weekdays := 0b0111110
	night := Schedule{Profile: "family", Days: weekdays, Start: 21 * 60, End: 7 * 60, On: true}
	day := Schedule{Profile: "strict", Days: 1 << 6, Start: 9 * 60, End: 17 * 60, On: true}
	c := Config{Profile: "default", Schedules: []Schedule{night, day}}
	cases := []struct {
		t    time.Time
		want string
		sch  bool
	}{
		{at(time.Monday, 22, 0), "family", true},
		{at(time.Tuesday, 6, 59), "family", true}, // morning after Monday night
		{at(time.Monday, 6, 0), "default", false}, // Sunday night not scheduled
		{at(time.Saturday, 6, 0), "family", true}, // morning after Friday night
		{at(time.Saturday, 22, 0), "default", false},
		{at(time.Saturday, 10, 0), "strict", true},
		{at(time.Saturday, 17, 0), "default", false}, // end is exclusive
		{at(time.Wednesday, 12, 0), "default", false},
	}
	for _, k := range cases {
		id, s := c.activeProfile(k.t)
		if id != k.want || s != k.sch {
			t.Errorf("%v: got %s %v want %s %v", k.t, id, s, k.want, k.sch)
		}
	}
	c.Schedules[0].On = false
	if id, _ := c.activeProfile(at(time.Monday, 22, 0)); id != "default" {
		t.Fatal(id)
	}
}

func TestCustomProfile(t *testing.T) {
	c := Config{Profile: "p-1", Profiles: []Profile{{ID: "p-1", Name: "Mine", Lists: []string{"easylist", "urlhaus"}}}}
	l := c.activeLists(time.Now(), "fr", "MA")
	if len(l) != 2 {
		t.Fatal(l)
	}
	if !c.profileByID("p-1").Custom {
		t.Fatal("custom flag")
	}
}
