# <img src="resources/img/voyage-burst.svg" height="25" alt=""> Voyage

[![Voyage Matrix room #voyage:matrix.org](https://img.shields.io/matrix/voyage:matrix.org.svg?label=%23voyage:matrix.org&logo=matrix&server_fqdn=matrix.org)](https://matrix.to/#/#voyage:matrix.org)
[![Donate Monero](https://img.shields.io/badge/Donate-Monero-FF6600?style=plastic&logo=monero&logoColor=white)](#donations)

Voyage is a [Matrix](https://matrix.org/) client for Android, based on [Element Classic](https://github.com/element-hq/element-android). The app can be run on every Android device with Android 4.0 Ice Cream Sandwich and later (API 14).

<p align="center">
  <img src="resources/img/screenshots/01-room-list.png" width="45%" alt="Room list">
  <img src="resources/img/screenshots/02-timeline.png" width="45%" alt="Timeline">
</p>
<p align="center">
  <img src="resources/img/screenshots/03-message-actions.png" width="45%" alt="Message actions">
  <img src="resources/img/screenshots/04-about.png" width="45%" alt="Help &amp; About">
</p>

# What's different

[CHANGES.md](CHANGES.md) lists the features, improvements and removals this fork
adds on top of Element Classic.

# Installing

Grab a nightly APK from the [releases](../../releases) tab.

Alternatively, build and install it yourself:

Debug: `./gradlew installDebug`

Release: `./gradlew install`

# Running on Android 4.0–4.3 (Ice Cream Sandwich / Jelly Bean)

On Android 4.0–4.3 (API 14–18) the app currently requires the device's Dalvik
bytecode verifier to be disabled, or it crashes when opening a room.

Disabling verification requires **root**. Voyage automatically prompts for and
applies this on first launch. Therefore, you must be rooted to use Voyage on
Android 4.0-4.3.

To re-enable verification, delete the file (if it holds nothing but this line)
and reboot:

```
adb shell su
rm /data/local.prop
reboot
```

# Donations

Monero:

```
85Em2xoemw7AvSrQL417MtLc1ytfT3TzBUiZU67q4tKTKNpwx6TQjKqasrTk1FUvnNdenESV6d4k8fFuoXbLowXT32AtCbb
```
