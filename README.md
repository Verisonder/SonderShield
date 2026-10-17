# SonderShield

Free, open-source ad and tracker blocker for Android, by Verisonder. No root, no account, no analytics.

It works as a local VPN that only carries DNS: the phone's resolver is pointed at an address inside the
tunnel, SonderShield answers blocked names with `0.0.0.0` / `::` and forwards the rest to the DNS server
you pick, encrypted (DNS over HTTPS) by default. All other traffic keeps its normal path, so there is
no speed or battery cost from routing.

## Features

- System-wide blocking with well-known lists: StevenBlack, AdGuard DNS, URLhaus, HaGeZi (Pro, Threat
  Intelligence, Xiaomi tracking), OISD. Add your own list by URL (hosts, AdBlock `||domain^` or plain
  domain lists).
- Your own block and allow rules (allow wins over everything), and a "test a domain" check that says
  which list or rule decides.
- Live DNS log with search and filters; tap a row to block or allow that domain.
- Per-app bypass for apps that break.
- DNS servers: Cloudflare, Quad9, Google, AdGuard (encrypted or plain) or a custom one.
- Answer cache, today / all-time counters, Quick Settings tile, start on boot, always-on VPN support,
  scheduled list updates (Wi-Fi only).

Planned: HTTPS filtering (cosmetic rules in browsers), then a Windows version using the same engine.

## Layout

- `engine/`: the filtering engine in Go, no dependencies. DNS and packet handling, list parsing,
  rules, cache, plain and DoH upstreams. Bound to Android with gomobile; the Windows app will use it
  directly.
- `app/`: the Android app (Kotlin, Jetpack Compose).
- `tools/build_engine.sh`: builds `app/libs/engine.aar` from `engine/`.

## Building

Needs JDK 17, the Android SDK with an NDK, and Go 1.23+.

```
tools/build_engine.sh          # engine -> app/libs/engine.aar
gradle assembleDebug
```

Engine tests: `cd engine && go test ./...`

## Private DNS

If Android's Private DNS is set to a specific provider, DNS goes straight to it and skips SonderShield.
The app warns when that is the case. Set Private DNS to Automatic or Off.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE). Block lists belong to their authors and keep their own licences.
