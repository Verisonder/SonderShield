package main

// Built-in block lists and DNS servers, same ids as the Android app (core/Catalog.kt)
// so a setting means the same thing on both.

type listDef struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	URL       string   `json:"url"`
	About     string   `json:"about"`
	Group     string   `json:"group"` // ads, privacy, security, family, regional, custom
	DefaultOn bool     `json:"defaultOn"`
	Langs     []string `json:"langs,omitempty"`
	Custom    bool     `json:"custom"`
}

var catalog = []listDef{
	{ID: "stevenblack", Name: "StevenBlack Unified", URL: "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", About: "Ads and malware. The classic hosts file.", Group: "ads", DefaultOn: true},
	{ID: "adguard-dns", Name: "AdGuard DNS filter", URL: "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", About: "Ads and trackers, tuned for DNS blocking.", Group: "ads", DefaultOn: true},
	{ID: "oisd-small", Name: "OISD small", URL: "https://small.oisd.nl/", About: "Ads only, very low false positives.", Group: "ads"},
	{ID: "adguard-base", Name: "AdGuard Base", URL: "https://filters.adtidy.org/extension/ublock/filters/2.txt", About: "AdGuard's main ad filter, an alternative to EasyList.", Group: "ads"},
	{ID: "easylist", Name: "EasyList", URL: "https://easylist.to/easylist/easylist.txt", About: "The main ad list for browsers.", Group: "ads"},
	{ID: "hagezi-pro", Name: "HaGeZi Pro", URL: "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt", About: "Stronger ads, tracking and telemetry list. Rarely breaks things.", Group: "privacy"},
	{ID: "hagezi-windows", Name: "HaGeZi Windows tracking", URL: "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/native.winoffice.txt", About: "Windows and Office telemetry. May affect some Microsoft features.", Group: "privacy"},
	{ID: "adguard-tracking", Name: "AdGuard Tracking Protection", URL: "https://filters.adtidy.org/extension/ublock/filters/3.txt", About: "Trackers and analytics.", Group: "privacy"},
	{ID: "adguard-social", Name: "AdGuard Social Media", URL: "https://filters.adtidy.org/extension/ublock/filters/4.txt", About: "Like and share buttons, social widgets and embeds.", Group: "privacy"},
	{ID: "urlhaus", Name: "URLhaus malware", URL: "https://urlhaus.abuse.ch/downloads/hostfile/", About: "Hosts currently spreading malware. Small.", Group: "security", DefaultOn: true},
	{ID: "hagezi-tif", Name: "HaGeZi Threat Intelligence", URL: "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.txt", About: "Phishing, scams and malware. Large.", Group: "security"},
	{ID: "rpilist-malware", Name: "Malware Domain List", URL: "https://raw.githubusercontent.com/RPiList/specials/master/Blocklisten/malware", About: "Community list of domains spreading malware.", Group: "security"},
	{ID: "phishing-army", Name: "Phishing Army", URL: "https://phishing.army/download/phishing_army_blocklist_extended.txt", About: "Phishing sites that steal passwords and cards (PhishTank and others).", Group: "security"},
	{ID: "hagezi-nsfw", Name: "HaGeZi NSFW", URL: "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/nsfw.txt", About: "Adult and porn sites.", Group: "family"},
	{ID: "hagezi-gambling", Name: "HaGeZi Gambling", URL: "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/gambling.medium.txt", About: "Betting, casino and gambling sites.", Group: "family"},
	{ID: "liste-fr", Name: "Liste FR", URL: "https://raw.githubusercontent.com/easylist/listefr/master/liste_fr.txt", About: "French ad networks and trackers.", Group: "regional", Langs: []string{"fr"}},
	{ID: "liste-ar", Name: "Liste AR", URL: "https://raw.githubusercontent.com/easylist/listear/master/Liste_AR.txt", About: "Arabic-language ad networks. Small.", Group: "regional", Langs: []string{"ar"}},
	{ID: "easylist-de", Name: "EasyList Germany", URL: "https://easylist.to/easylistgermany/easylistgermany.txt", About: "German ad networks and trackers.", Group: "regional", Langs: []string{"de"}},
	{ID: "abpvn", Name: "ABPVN", URL: "https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn.txt", About: "Vietnamese ad networks.", Group: "regional", Langs: []string{"vi"}},
}

var threatLists = map[string]bool{"urlhaus": true, "hagezi-tif": true, "rpilist-malware": true, "phishing-army": true}

type upstreamDef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Spec string `json:"spec"`
}

var upstreams = []upstreamDef{
	{"cf-doh", "Cloudflare (encrypted)", "doh https://cloudflare-dns.com/dns-query 1.1.1.1 1.0.0.1 2606:4700:4700::1111"},
	{"quad9-doh", "Quad9 (encrypted, blocks malware)", "doh https://dns.quad9.net/dns-query 9.9.9.9 149.112.112.112 2620:fe::fe"},
	{"google-doh", "Google (encrypted)", "doh https://dns.google/dns-query 8.8.8.8 8.8.4.4 2001:4860:4860::8888"},
	{"cf-udp", "Cloudflare (plain)", "udp 1.1.1.1 1.0.0.1"},
	{"quad9-udp", "Quad9 (plain)", "udp 9.9.9.9 149.112.112.112"},
}

const defaultUpstream = "cf-doh"

func upstreamByID(id string) *upstreamDef {
	for i := range upstreams {
		if upstreams[i].ID == id {
			return &upstreams[i]
		}
	}
	return nil
}

func defaultLists(lang, country string) []string {
	want := map[string]bool{lang: true}
	switch country {
	case "MA", "DZ", "TN":
		want["fr"], want["ar"] = true, true
	}
	var out []string
	for _, l := range catalog {
		on := l.DefaultOn
		for _, x := range l.Langs {
			on = on || want[x]
		}
		if on {
			out = append(out, l.ID)
		}
	}
	return out
}
