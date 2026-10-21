# CallNotes — guide for LLM contributors

Android app that records phone calls, transcribes and summarizes them **on the device**
with Gemma 4, and turns what was said into tasks. Hebrew-first UI. Owner: Liad.

Read `docs/wiki/README.md` before changing anything non-trivial. The wiki holds the
facts that are expensive to rediscover (Android call-audio rules, device findings,
decisions already taken and why).

## Hard constraints — do not "fix" these away

- **No cloud, ever.** The owner's core promise is privacy: audio, transcripts and
  summaries never leave the device, and the app uses no internet services. Network is
  used only to download model files.
- **Call audio rules.** An ordinary app gets silence from the mic during a call; only an
  accessibility service may capture. `CAPTURE_AUDIO_OUTPUT` is system-only. See
  `docs/wiki/android-call-audio.md` before proposing any new capture approach — most
  "obvious" ideas are already ruled out there.
- **No screen hijacking.** The accessibility service may tap the speaker button on the
  in-call screen that is already showing, and nothing else. Automating the dialer's
  share flow was explicitly rejected by the owner.
- **Models are downloaded, never bundled** in the APK. Transcriber and Analyzer are
  separate, swappable interfaces (`inference/Models.kt`).
- **Distribution is a sideloaded APK** (GitHub Releases). Google Play bans
  accessibility-based call recording.

## Build, run, release

- `./gradlew assembleDebug` — needs the Android SDK (Android Studio or CI).
- Every push to `main` builds on GitHub Actions (`.github/workflows/build.yml`) and
  publishes a prerelease with the APK. **CI is the compile check** — if your session
  can't reach Google Maven, push and read the Actions result.
- Debug signing uses the committed `keystore/debug.keystore` so builds install as
  updates of each other. Don't replace it.
- Bump `versionName` in `app/build.gradle.kts` when a roadmap version is reached.

## Code map

```
app/src/main/java/com/callnotes/
  capture/    call detection, recording, speakerphone, call-log lookup
  data/       Room (CallEntity, CallDao, AppDatabase)
  inference/  Transcriber / Analyzer interfaces, model catalog, Whisper (whisper/),
              speakers (speakers/), Gemma
  processing/ foreground service with two parallel workers (transcribe, summary), log
  ui/         Compose UI, setup checks, audio player
  util/       prefs, notifications
app/src/main/java/com/k2fsa/sherpa/onnx/  sherpa-onnx Kotlin API (verbatim copy)
app/src/main/cpp/   JNI bridge to whisper.cpp (third_party/whisper.cpp submodule)
scripts/fetch-sherpa-onnx.sh  prebuilt diarization runtime (run before building)
```

## Working agreements

- **Keep the knowledge current.** When you learn a platform fact, a device behaviour,
  or make a decision, update the matching wiki page in the same commit
  (`decisions.md` for decisions, `devices.md` for device findings, `roadmap.md` for
  status). The owner also keeps a Hebrew planning doc in claude.ai; it is updated
  alongside the wiki.
- UI strings are Hebrew; layout is forced RTL. Code, comments and the wiki are English.
- Commits: imperative subject, body explains *why*. One logical change per commit.
- Prefer verifying over assuming: any device-dependent behaviour gets a diagnostic
  surfaced in the UI (see the per-call chips) so a test call answers the question.
