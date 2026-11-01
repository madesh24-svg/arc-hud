# Changelog

Release numbers follow the CI run (`build-N` = version 1.0.N).

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
