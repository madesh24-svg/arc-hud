# ARC HUD

A lightweight, animated heads-up display for Android TV boxes. Runs fully on the device:
no internet permission, no background service, no telemetry.

Shows live: IP, Wi-Fi MAC, SSID, signal, link speed, Private DNS, RAM, storage, network
throughput, uptime, clock. Works as a launcher app and as a screensaver (Daydream).

Latest APK: `releases/latest/download/arc-hud.apk`

Personalise the on-screen text once:

    am start -n io.archud/.HudActivity --es title "MY TITLE" --es subtitle "MY SUBTITLE" --es salutation "NAME"

The Wi-Fi name needs the location permission on Android 9+:

    pm grant io.archud android.permission.ACCESS_COARSE_LOCATION

## Building

CI builds and publishes a signed APK on every push to `main`. Signing uses two repository
secrets: `ARCHUD_KEYSTORE_B64` (base64 PKCS12 keystore, alias `archud`) and
`ARCHUD_KEYSTORE_PASSWORD`. Forks need their own key.

## License

MIT
