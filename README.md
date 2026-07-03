# Voyage

Voyage is a [Matrix](https://matrix.org/) client for Android, based on [Element Android](https://github.com/element-hq/element-android). The app can be run on every Android device with Android 4.0 Ice Cream Sandwich and later (API 14).

# Installing

Debug: `./gradlew installDebug`

Release: `./gradlew install`

# Running on Android 4.0–4.3 (Ice Cream Sandwich / Jelly Bean)

On Android 4.0–4.3 (API 14–18) the app currently requires the device's Dalvik
bytecode verifier to be disabled, or it crashes when opening a room.

Dalvik reserves a **fixed 8 MB LinearAlloc region** for the metadata (vtables,
field/method structs) of every loaded class, and never frees it. When the verifier
checks a class it eagerly resolves (and therefore loads) every class that class
references, even from branches that never run. Opening a room loads enough classes
that the running total exceeds 8 MB and `dvmDefineClass` segfaults
(`LinearAlloc exceeded capacity (8388608)`). Disabling verification stops the
verifier's eager class loading and keeps the app under the limit.

This affects the **Dalvik** runtime, and only the versions whose LinearAlloc region
is small enough to overflow:

- **Android 4.0–4.3 (Ice Cream Sandwich and Jelly Bean, API 14–18):** ~8 MB
  LinearAlloc - **affected**.

- **Android 4.4 (KitKat):** Dalvik, but the LinearAlloc region is large enough that
  the app fits, so it runs without the flag.

- **Android 5.0+ (API 21+):** ART, which has no LinearAlloc region - unaffected.

Disabling verification requires **root**. Persist the property in `/data/local.prop`:

```
adb shell su
echo 'dalvik.vm.extra-opts=-Xverify:none' >> /data/local.prop
chmod 644 /data/local.prop
reboot
```

After the reboot, confirm it took effect with `adb shell getprop dalvik.vm.extra-opts`
(should print `-Xverify:none`).

To re-enable verification, delete the file (if it holds nothing but this line) and
reboot:

```
adb shell su
rm /data/local.prop
reboot
```
