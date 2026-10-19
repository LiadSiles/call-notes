# Android call audio — what is and isn't possible

_Last verified: 2026-10-19._ This page exists so nobody spends a day re-discovering it.

## The rules

1. **Ordinary apps get silence from the mic while a call is active.** Android's
   capture policy ("Sharing audio input" in the Android docs) says that during a voice
   call (`AudioManager` mode `MODE_IN_CALL` / `MODE_IN_COMMUNICATION`) the call always
   gets audio and another app can capture only if it is an **accessibility service**,
   or a privileged pre-installed app holding `CAPTURE_AUDIO_OUTPUT`.
2. **Direct call audio (`VOICE_UPLINK` / `VOICE_DOWNLINK` / `VOICE_CALL`) requires
   `CAPTURE_AUDIO_OUTPUT`**, a signature/privileged permission. A user cannot grant it,
   not through settings, ADB or Shizuku.
3. **The playback-capture API (Android 10+) excludes call audio** by design.
4. **The cellular call path doesn't touch app-visible audio.** The modem/DSP routes
   downlink audio to the earpiece; there is nothing in the app audio stack to tap.
5. **Being the default dialer (`InCallService`) gives call control, not audio.**
6. **Wi-Fi calling / VoLTE are still carrier calls** — same restrictions.
7. **No permission can be "borrowed" from a system app** that holds it (that would be a
   confused-deputy hole). Each system recorder keeps its files private.
8. **Google Play bans accessibility-based call recording** (since 2022). Our capture
   path A therefore ships as a sideloaded APK.
9. **Android 13+ "restricted settings":** an APK installed from a file manager or
   browser can't enable an accessibility service until the user allows restricted
   settings in App info → ⋮. ADB / Android Studio installs are not restricted.

## Consequences for this app

- Path A records the **microphone** from an accessibility service and moves the call
  to the **loudspeaker** so the mic hears the far side.
- Path B reads the **OEM dialer's own recordings** where they land in shared storage
  (Samsung, OnePlus/Oppo/Realme, Vivo, Xiaomi dialer). Google's dialer stores them
  privately → no path B on Google-dialer phones.
- Full-quality capture without speaker is only possible where path B works, through
  VoIP (we'd own the call), or with hardware (a Bluetooth device in the audio path).

## Ruled-out ideas (with the reason)

| Idea | Why not |
|---|---|
| Automate "Share" in Google's dialer after each call | Works, but takes over the screen — rejected by the owner |
| Use another system app (recorder, screen recorder, Live Caption) | No cross-app delegation; screen recorder excludes call audio; Live Caption is Pixel/English only |
| App pretends to be a Bluetooth headset for its own phone | Needs `BLUETOOTH_PRIVILEGED`; a phone can't pair with itself |
| Virtual/remote-submix audio device | Capturing it needs `CAPTURE_AUDIO_OUTPUT` again |
| Default dialer role for recording | Control only, no audio. **But** it does give a sanctioned speaker switch (`InCallService.setAudioRoute`) — kept as a fallback for path A if the 0.1 test shows API/tap can't enable the speaker; cost is building a full dialer UI. |
| Root | Works (install as priv-app) but not a product path |
