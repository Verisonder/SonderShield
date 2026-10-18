package com.verisonder.sondershield.ui

import com.verisonder.sondershield.core.BlockList

/** A list's description in the app's language. */
fun listAbout(l: BlockList, lang: String): String = when (lang) {
    "fr" -> aboutFr(l.id, l.about)
    "de" -> aboutDe(l.id, l.about)
    else -> l.about
}

/** French descriptions of the built-in lists. */
private fun aboutFr(id: String, en: String): String = when (id) {
    "stevenblack" -> "Pubs et malwares. Le fichier hosts classique."
    "adguard-dns" -> "Pubs et traqueurs, pensé pour le blocage DNS."
    "urlhaus" -> "Sites qui diffusent des malwares en ce moment. Petite."
    "hagezi-pro" -> "Liste plus forte : pubs, traçage et télémétrie. Casse rarement quelque chose."
    "hagezi-tif" -> "Phishing, arnaques et malwares. Grande."
    "hagezi-xiaomi" -> "Télémétrie Xiaomi / HyperOS. Peut masquer certaines pubs et thèmes MIUI."
    "oisd-small" -> "Pubs uniquement, très peu de faux positifs."
    "liste-fr" -> "Régies pub et traqueurs français."
    "liste-ar" -> "Régies pub en langue arabe. Petite."
    "easylist-de" -> "Régies pub et traqueurs allemands."
    "easylist-es" -> "Régies pub et traqueurs espagnols."
    "easylist-it" -> "Régies pub et traqueurs italiens."
    "easylist-pt" -> "Régies pub et traqueurs portugais."
    "easylist" -> "La liste de pubs principale des navigateurs. Masque les emplacements de pub quand le filtrage HTTPS est activé."
    "ublock" -> "Contre les murs anti-bloqueur et les pubs tenaces (scriptlets). Pour le filtrage HTTPS."
    "adguard-base" -> "Le filtre pub principal d'AdGuard, alternative à EasyList. Avec des règles de page."
    "adguard-mobile" -> "Pubs dans les applis et les sites mobiles."
    "adguard-tracking" -> "Traqueurs et statistiques."
    "adguard-social" -> "Boutons j'aime et partager, widgets et contenus intégrés des réseaux sociaux."
    "rpilist-malware" -> "Liste communautaire de domaines qui diffusent des malwares."
    "phishing-army" -> "Sites de phishing qui volent mots de passe et cartes (PhishTank et autres)."
    "hagezi-nsfw" -> "Sites pour adultes et pornographiques."
    "hagezi-gambling" -> "Paris, casinos et jeux d'argent."
    "abpvn" -> "Régies pub vietnamiennes."
    else -> en
}

/** German descriptions of the built-in lists. */
private fun aboutDe(id: String, en: String): String = when (id) {
    "stevenblack" -> "Werbung und Malware. Die klassische hosts-Datei."
    "adguard-dns" -> "Werbung und Tracker, für DNS-Blocking gemacht."
    "urlhaus" -> "Seiten, die gerade Malware verbreiten. Klein."
    "hagezi-pro" -> "Stärkere Liste: Werbung, Tracking und Telemetrie. Macht selten etwas kaputt."
    "hagezi-tif" -> "Phishing, Betrug und Malware. Groß."
    "hagezi-xiaomi" -> "Xiaomi-/HyperOS-Telemetrie. Kann einige MIUI-Werbung und Designs ausblenden."
    "oisd-small" -> "Nur Werbung, sehr wenige Fehlalarme."
    "liste-fr" -> "Französische Werbenetzwerke und Tracker."
    "liste-ar" -> "Arabischsprachige Werbenetzwerke. Klein."
    "easylist-de" -> "Deutsche Werbenetzwerke und Tracker."
    "easylist-es" -> "Spanische Werbenetzwerke und Tracker."
    "easylist-it" -> "Italienische Werbenetzwerke und Tracker."
    "easylist-pt" -> "Portugiesische Werbenetzwerke und Tracker."
    "easylist" -> "Die wichtigste Werbeliste für Browser. Blendet Werbeflächen aus, wenn die HTTPS-Filterung an ist."
    "ublock" -> "Gegen Adblock-Sperren und hartnäckige Werbung (Scriptlets). Für die HTTPS-Filterung."
    "adguard-base" -> "Der Hauptfilter von AdGuard, eine Alternative zu EasyList. Mit Seitenregeln."
    "adguard-mobile" -> "Werbung in Apps und mobilen Seiten."
    "adguard-tracking" -> "Tracker und Analysedienste."
    "adguard-social" -> "Like- und Teilen-Knöpfe, Social-Widgets und Einbettungen."
    "rpilist-malware" -> "Community-Liste von Domains, die Malware verbreiten."
    "phishing-army" -> "Phishing-Seiten, die Passwörter und Karten stehlen (PhishTank und andere)."
    "hagezi-nsfw" -> "Erotik- und Pornoseiten."
    "hagezi-gambling" -> "Wett-, Casino- und Glücksspielseiten."
    "abpvn" -> "Vietnamesische Werbenetzwerke."
    else -> en
}
