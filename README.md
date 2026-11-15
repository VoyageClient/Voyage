# <img src="resources/img/voyage-burst.svg" height="25" alt=""> Voyage

[![Voyage Matrix room #voyage:matrix.org](https://img.shields.io/matrix/voyage:matrix.org.svg?label=%23voyage:matrix.org&logo=matrix&server_fqdn=matrix.org)](https://matrix.to/#/#voyage:matrix.org)
[![Donate Monero](https://img.shields.io/badge/Donate-Monero-FF6600?style=plastic&logo=monero&logoColor=white)](#donations)

Voyage is a [Matrix](https://matrix.org/) client for Android, based on [Element Classic](https://github.com/element-hq/element-android). The app can be run on every Android device with Android 4.0 Ice Cream Sandwich and later (API 14).

[<img src="resources/img/f-droid-badge.png" alt="Get it on F-Droid" height="60">](https://f-droid.org/app/im.voyage.app)

<p align="center">
  <img src="resources/img/screenshots/01-room-list.png" width="45%" alt="Room list">
  <img src="resources/img/screenshots/02-timeline.png" width="45%" alt="Timeline">
</p>
<p align="center">
  <img src="resources/img/screenshots/03-room-overview.png" width="45%" alt="Room overview">
  <img src="resources/img/screenshots/04-image-editor.png" width="45%" alt="Image editor">
</p>

# What's different

[CHANGES.md](CHANGES.md) lists the features, improvements and removals this fork
adds on top of Element Classic.

# Installing

Install Voyage from [F-Droid](https://f-droid.org/app/im.voyage.app), or grab an APK from the [releases](../../releases) tab.

Alternatively, build and install it yourself:

Build release APKs: `make`. Outputs are in `vector-app/build/outputs/apk/release/`.

Debug install: `make installDebug`

Release install: `make install`

Run linting with `make lint` and the unit test suite with `make test`.

# AI Disclosure

Voyage is developed utilizing LLMs/AI. If you are bothered by this, then I encourage you to find another client. First and foremost, I made this client out of my own personal desire to improve upon Element Classic, and LLMs allowed me to do so as an individual within a reasonable timespan. This client is more than anything else something I made for myself. I'm offering it to you to use it, because sharing is caring. You are not entitled to an LLM-free copy of my hobby project. All changes are reviewed by a human (me!), and QA testing is conducted before a release is tagged. I review all the strings I introduce into this app to ensure that their wording is satisfactory.

# Support

If you have any problems, I encourage you to report them in an issue, or to join the [Matrix room](https://matrix.to/#/#voyage:matrix.org) and ask about it there. Attaching a log export often helps diagnose a problem if it cannot be reliably reproduced. You can export your logs from `... > Export logs` on the main page of the app. The client cannot improve if you encounter a problem and choose to ignore it. I am one person, and this is a big codebase. I can only encounter so many bugs myself. As the feature set grows, so does the possibility of introducing bugs.

# Running on Android 4.0–4.3

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
