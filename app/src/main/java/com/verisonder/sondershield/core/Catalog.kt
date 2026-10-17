package com.verisonder.sondershield.core

/** A block list the app can download. Custom lists get ids starting with "custom-". */
data class BlockList(
    val id: String,
    val name: String,
    val url: String,
    val about: String,
    val defaultOn: Boolean = false,
    /** Languages it's meant for: switched on by default when the phone uses one of them. */
    val langs: Set<String> = emptySet(),
)

object Catalog {
    val lists = listOf(
        BlockList(
            "stevenblack", "StevenBlack Unified",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "Ads and malware. The classic hosts file.", defaultOn = true,
        ),
        BlockList(
            "adguard-dns", "AdGuard DNS filter",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "Ads and trackers, tuned for DNS blocking.", defaultOn = true,
        ),
        BlockList(
            "urlhaus", "URLhaus malware",
            "https://urlhaus.abuse.ch/downloads/hostfile/",
            "Hosts currently spreading malware. Small.", defaultOn = true,
        ),
        BlockList(
            "hagezi-pro", "HaGeZi Pro",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt",
            "Stronger ads, tracking and telemetry list. Rarely breaks things.",
        ),
        BlockList(
            "hagezi-tif", "HaGeZi Threat Intelligence",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.txt",
            "Phishing, scams and malware. Large.",
        ),
        BlockList(
            "hagezi-xiaomi", "HaGeZi Xiaomi tracking",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/native.xiaomi.txt",
            "Xiaomi / HyperOS telemetry. May hide some MIUI ads and themes.",
        ),
        BlockList(
            "oisd-small", "OISD small",
            "https://small.oisd.nl/",
            "Ads only, very low false positives.",
        ),
        // Regional: ad networks of one language area. On by default for the phone's languages.
        BlockList(
            "liste-fr", "Liste FR",
            "https://raw.githubusercontent.com/easylist/listefr/master/liste_fr.txt",
            "French ad networks and trackers.", langs = setOf("fr"),
        ),
        BlockList(
            "liste-ar", "Liste AR",
            "https://raw.githubusercontent.com/easylist/listear/master/Liste_AR.txt",
            "Arabic-language ad networks. Small.", langs = setOf("ar"),
        ),
        BlockList(
            "easylist-de", "EasyList Germany",
            "https://easylist.to/easylistgermany/easylistgermany.txt",
            "German ad networks and trackers.", langs = setOf("de"),
        ),
        BlockList(
            "easylist-es", "EasyList Spanish",
            "https://easylist-downloads.adblockplus.org/easylistspanish.txt",
            "Spanish ad networks and trackers.", langs = setOf("es"),
        ),
        BlockList(
            "easylist-it", "EasyList Italy",
            "https://easylist-downloads.adblockplus.org/easylistitaly.txt",
            "Italian ad networks and trackers.", langs = setOf("it"),
        ),
        BlockList(
            "easylist-pt", "EasyList Portuguese",
            "https://easylist-downloads.adblockplus.org/easylistportuguese.txt",
            "Portuguese ad networks and trackers.", langs = setOf("pt"),
        ),
    )

    val defaultIds: Set<String> = lists.filter { it.defaultOn }.map { it.id }.toSet()

    /** Defaults plus the regional lists for these languages (and, in the Maghreb, French and Arabic). */
    fun defaultsFor(langs: Collection<String>, country: String): Set<String> {
        val want = langs.toMutableSet()
        if (country.uppercase() in setOf("MA", "DZ", "TN")) want += setOf("fr", "ar")
        return defaultIds + lists.filter { l -> l.langs.any { it in want } }.map { it.id }
    }
}

/** A DNS server the engine forwards allowed queries to. */
data class Upstream(val id: String, val name: String, val spec: String)

object Upstreams {
    val all = listOf(
        Upstream("cf-doh", "Cloudflare (encrypted)", "doh https://cloudflare-dns.com/dns-query 1.1.1.1 1.0.0.1 2606:4700:4700::1111"),
        Upstream("quad9-doh", "Quad9 (encrypted, blocks malware)", "doh https://dns.quad9.net/dns-query 9.9.9.9 149.112.112.112 2620:fe::fe"),
        Upstream("google-doh", "Google (encrypted)", "doh https://dns.google/dns-query 8.8.8.8 8.8.4.4 2001:4860:4860::8888"),
        Upstream("adguard-doh", "AdGuard unfiltered (encrypted)", "doh https://unfiltered.adguard-dns.com/dns-query 94.140.14.140 94.140.14.141"),
        Upstream("cf-udp", "Cloudflare (plain)", "udp 1.1.1.1 1.0.0.1"),
        Upstream("quad9-udp", "Quad9 (plain)", "udp 9.9.9.9 149.112.112.112"),
    )
    const val DEFAULT = "cf-doh"
    const val CUSTOM = "custom"

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
