# Changelog

Release numbers follow the CI run (`build-N` = version 1.0.N).

## 1.0.20
- The Apps row no longer lists the device's own Settings app a second time: the fixed TV SETTINGS tile
  already opens the same screen.

## 1.0.19
- Devices: no device should hide. Discovery now nudges every address twice (forcing an ARP lookup even for
  devices that ignore pings or are slow in Wi-Fi power save), asks each unnamed device for its mDNS name
  (an answer also marks it ACTIVE), and remembers every device seen in the last 30 days, listing those
  not on the network as OFFLINE with "last seen ... ago".

## 1.0.18
- ARC SEARCH: focus starts on SmartTube's first result even when the web section answers first.

## 1.0.17
- ARC SEARCH: a voice search (or the SEARCH tile) shows SmartTube's own top 10 results (MORE shows
  10 more) and web results (Wikipedia articles plus a DuckDuckGo row) that open in the TV browser.
  Sources are pluggable (SearchProvider); a switch in ARC SETTINGS turns the voice hook off.
- Network rule: ARC SEARCH may fetch only Wikipedia's search API and YouTube thumbnail images,
  over HTTPS, only when a search runs (SafeHttp allowlist).

## 1.0.16
- Small version line in the bottom-right corner of the home screen and HUD.

## 1.0.15
- ARC HOME can be the TV's home screen (HOME registration). When it is the default home, BACK no
  longer leaves it, and HOME closes any popup and returns to the first tile.

## 1.0.14
- Kotlin enabled alongside Java (Kotlin 2.0.21). New code is Kotlin; files move over when touched.
  First Kotlin file: UpdatedReceiver. Release builds are now shrunk with R8.

## 1.0.13
- Name devices: OK (or MENU) on a device in NETWORK DEVICES; names are kept on the TV, keyed by MAC.
- ACTIVE / STANDBY per device: ACTIVE answers pings or UPnP, STANDBY only answers the network check.
- NEW = first seen in the last 24 hours and not named yet; devices from the first scan are the baseline.

## 1.0.12
- After an in-app update the home screen reopens by itself and shows "ARC HUD updated to 1.0.x"
  (Android closes an app while updating it, which looked like nothing happened).

## 1.0.11
- One launcher entry: the app tile now opens the home screen (most TV launchers show one tile per
  app, so the full-screen HUD was the only thing reachable). The HUD moved to a FULL HUD tile.

## 1.0.10
- Network devices: DEVICES tile and screen in ARC HOME, device count on the HUD. Scans the local
  network once a minute while shown (never in the background): ping sweep, ARP table, router DNS
  names, UPnP/SSDP names and models, router uptime and public IP via UPnP where offered.
- Manufacturer names from a table CI builds from Wireshark's OUI list and publishes with each release;
  the device downloads it from this repo weekly, so MAC addresses never leave the network.
- Network rule: the scanner may only reach local addresses; the internet is only this repo.

## 1.0.9
- Changelog added (also the first release delivered through the in-app updater).

## 1.0.8
- Native self-update from ARC SETTINGS, locked to this repository's releases (HTTPS, host allowlist,
  same-signature install). The only network use in the app.
- Hidden tiles: choose which to show again (or all) in ARC SETTINGS.
- Duplicate input names are numbered (AV 1, AV 2).
- ARC HOME tiles wrap into a grid and scroll only when they do not fit.

## 1.0.7
- MENU on a tile opens hide / pin (IR remotes cannot long-press).

## 1.0.6
- ARC SETTINGS screen, hide / pin tiles, tile names, idle-safety guard for screensavers,
  animation speed. CI moved to checkout/setup-java v5 on ubuntu-24.04; AGP 8.7.3, compileSdk 35.

## 1.0.4
- ARC HOME preview: live HUD background with Inputs and Apps rows.

## 1.0.3
- Screensaver setup assistant with restore.

## 1.0.1 - 1.0.2
- Live HUD and screensaver; MAC / SSID fallbacks for devices that hide them.
