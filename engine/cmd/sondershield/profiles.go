package main

import "time"

// Profiles are ready-made sets of block lists, so someone who doesn't know which lists to
// pick can choose "Default", "Strict", "Family"... Same ids as the Android app
// (core/Profiles.kt). A profile only decides which lists are on; your own block/allow
// rules, the firewall and everything else stay as they are.

// Profile is one set of lists. Built-in ones add the regional lists for your language;
// ones you make hold exactly the lists you picked.
type Profile struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	About    string   `json:"about"`
	Icon     string   `json:"icon"` // shield, strict, family, game, lock, star, moon, work
	Lists    []string `json:"lists"`
	Regional bool     `json:"regional"`
	Custom   bool     `json:"custom"`
}

// Schedule turns a profile on during a time window on the chosen days. Days is a bit mask
// (bit 0 = Sunday ... bit 6 = Saturday); Start and End are minutes after midnight, and a
// window whose End is not after its Start runs past midnight into the next day.
type Schedule struct {
	ID      string `json:"id"`
	Profile string `json:"profile"`
	Days    int    `json:"days"`
	Start   int    `json:"start"`
	End     int    `json:"end"`
	On      bool   `json:"on"`
}

var builtinProfiles = []Profile{
	{ID: "default", Name: "Default", About: "Balanced blocking: basic ads and trackers", Icon: "shield",
		Lists: []string{"stevenblack", "adguard-dns", "urlhaus"}, Regional: true},
	{ID: "strict", Name: "Strict", About: "Aggressive blocking: ads, trackers, analytics, telemetry", Icon: "strict",
		Lists: []string{"stevenblack", "adguard-dns", "urlhaus", "hagezi-pro", "adguard-tracking", "hagezi-windows", "phishing-army"}, Regional: true},
	{ID: "family", Name: "Family", About: "Safe browsing: ads, adult content, gambling", Icon: "family",
		Lists: []string{"stevenblack", "adguard-dns", "urlhaus", "hagezi-nsfw", "hagezi-gambling"}, Regional: true},
	{ID: "gaming", Name: "Gaming", About: "Light blocking: basic ads only, fewest broken games", Icon: "game",
		Lists: []string{"oisd-small", "urlhaus"}},
	{ID: "strict-family", Name: "Strict Family", About: "Maximum blocking: ads, trackers, adult content & gambling", Icon: "lock",
		Lists: []string{"stevenblack", "adguard-dns", "urlhaus", "hagezi-pro", "adguard-tracking", "hagezi-windows", "phishing-army", "hagezi-nsfw", "hagezi-gambling"}, Regional: true},
}

// allProfiles is the built-in profiles followed by yours.
func (c *Config) allProfiles() []Profile {
	out := append([]Profile(nil), builtinProfiles...)
	for _, p := range c.Profiles {
		p.Custom = true
		out = append(out, p)
	}
	return out
}

func (c *Config) profileByID(id string) *Profile {
	for _, p := range c.allProfiles() {
		if p.ID == id {
			p := p
			return &p
		}
	}
	return nil
}

// covers reports whether the schedule is running at t (local time).
func (s Schedule) covers(t time.Time) bool {
	if !s.On || s.Days == 0 {
		return false
	}
	m := t.Hour()*60 + t.Minute()
	day := int(t.Weekday())
	has := func(d int) bool { return s.Days&(1<<uint((d+7)%7)) != 0 }
	if s.End > s.Start { // same day
		return has(day) && m >= s.Start && m < s.End
	}
	// Runs past midnight: the evening part belongs to today, the morning part to yesterday.
	return (has(day) && m >= s.Start) || (has(day-1) && m < s.End)
}

// activeProfile is the profile in use at t: a running schedule wins (the first one in the
// list, if several overlap), otherwise the profile you chose. "" means your own picks.
func (c *Config) activeProfile(t time.Time) (id string, scheduled bool) {
	for _, s := range c.Schedules {
		if s.covers(t) && c.profileByID(s.Profile) != nil {
			return s.Profile, true
		}
	}
	if c.Profile != "" && c.profileByID(c.Profile) != nil {
		return c.Profile, false
	}
	return "", false
}

// activeLists is the list ids in use at t, for this language and country.
func (c *Config) activeLists(t time.Time, lang, country string) []string {
	id, _ := c.activeProfile(t)
	if id == "" {
		return c.Lists
	}
	p := c.profileByID(id)
	out := append([]string(nil), p.Lists...)
	if p.Regional {
		for _, r := range regionalLists(lang, country) {
			if !contains(out, r) {
				out = append(out, r)
			}
		}
	}
	return out
}

// regionalLists is the regional lists for a language (in the Maghreb, French and Arabic too).
func regionalLists(lang, country string) []string {
	want := map[string]bool{lang: true}
	switch country {
	case "MA", "DZ", "TN":
		want["fr"], want["ar"] = true, true
	}
	var out []string
	for _, l := range catalog {
		for _, x := range l.Langs {
			if want[x] {
				out = append(out, l.ID)
				break
			}
		}
	}
	return out
}
