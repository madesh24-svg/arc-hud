# ARC HUD

A lightweight, animated heads-up display for Android TV boxes. Runs fully on the device:
no background service, no telemetry. The only network use is the optional self-update: it runs
only when the user presses "Check for update", over HTTPS, and the code refuses any address other
than this repository's releases (and GitHub's file servers they redirect to). Android installs an
update only if it is signed with the same key.

Updating needs the app to be allowed to install packages. Where the TV has no setting for that,
the device owner grants it once:

    adb shell appops set io.archud REQUEST_INSTALL_PACKAGES allow

Shows live: IP, Wi-Fi MAC, SSID, signal, link speed, Private DNS, RAM, storage, network
throughput, uptime, clock. Works as a launcher app and as a screensaver (Daydream).

Latest APK: `releases/latest/download/arc-hud.apk`

Personalise the on-screen text once:

    am start -n io.archud/.HudActivity --es title "MY TITLE" --es subtitle "MY SUBTITLE" --es salutation "NAME"

Android hides the Wi-Fi MAC (and sometimes the SSID) from apps. If the HUD shows RESTRICTED or
HIDDEN, store the values once on the device (read them with adb, they are never committed):

    am start -n io.archud/.HudActivity --es mac "$(cat /sys/class/net/wlan0/address)" --es ssid "MyNetwork"

(or type them in ARC SETTINGS > Network display)

The Wi-Fi name also needs the location permission on Android 9+:

    pm grant io.archud android.permission.ACCESS_COARSE_LOCATION

## Building

CI builds and publishes a signed APK on every push to `main`. Signing uses two repository
secrets: `ARCHUD_KEYSTORE_B64` (base64 PKCS12 keystore, alias `archud`) and
`ARCHUD_KEYSTORE_PASSWORD`. Forks need their own key.

## License

MIT

## Install on a TV (adb)

    adb shell am start -a android.intent.action.VIEW -d https://github.com/madesh24-svg/arc-hud/releases/latest/download/arc-hud.apk
    # or, after downloading on the TV:
    adb shell pm install -r /sdcard/Download/arc-hud.apk
    adb shell pm grant io.archud android.permission.ACCESS_COARSE_LOCATION

## ARC HOME (launcher, preview)

A second entry, ARC HOME, is a TV home screen: the live HUD as background with an Inputs row
(HDMI and other passthrough inputs from the TV input list, plus Settings) and an Apps row.
Press MENU on a tile (or long-press OK, if your remote supports it) to hide it or pin it to the front; a HIDDEN (n) tile at the end of the
row brings hidden tiles back. Phase 1 opens like a normal app; it is not registered as the system Home yet.

## ARC SETTINGS

MENU on the remote (or the ARC SETTINGS tile) opens the app's own settings: greeting name, title,
subtitle, status line, hidden/pinned tiles, Wi-Fi name / MAC display, screensaver, animation speed,
update download and reset. Everything is stored on the device only.

## Network devices

ARC HOME's DEVICES tile lists what is on the home network: name, IP, MAC, manufacturer, type/model
and how long it has been online (as seen by this device; other devices do not publish their uptime).
New devices are flagged. Sources: ping sweep, ARP table, router DNS names, UPnP/SSDP descriptions,
and the router's uptime / public IP over UPnP where the router offers it. Scans run once a minute
only while the HUD or the device list is on screen. The scanner can only reach local addresses
(private / link-local ranges, enforced in code). Manufacturer names come from `oui.tsv.gz`, which CI
builds from Wireshark's public OUI list and attaches to each release; the device fetches it from this
repo weekly and looks names up locally.

## Screensaver setup

On launch the app checks whether it is the screensaver and, if not, asks. Nothing changes unless
the user picks YES. Press OK or MENU on the HUD any time to manage it (idle time, restore original).

- Devices with a screensaver settings screen: YES opens it so the user confirms there.
- Devices without one: the app can apply the change itself only if the device owner has granted it
  the permission once:

      adb shell pm grant io.archud android.permission.WRITE_SECURE_SETTINGS
      adb shell appops set io.archud WRITE_SETTINGS allow

  Otherwise it explains that the device does not allow it. RESTORE ORIGINAL puts back the previous
  screensaver and idle time.
