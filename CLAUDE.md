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
  publishes a prerelease with the APK — the R8-optimised **release** build (0.20.1),
  signed with the committed key; unit tests run on debug. Classes reached from native code
  need keep rules in `app/proguard-rules.pro`. **CI is the compile check** — if your session
  can't reach Google Maven, push and read the Actions result.
- Debug signing uses the committed `keystore/debug.keystore` so builds install as
  updates of each other. Don't replace it.
- Bump `versionName` in `app/build.gradle.kts` when a roadmap version is reached.

## Code map

```
app/src/main/java/com/callnotes/
  audio/      WAV I/O, decoding to the working format, AAC compression
  capture/    call detection, recording, speakerphone, call-log lookup
  data/       Room (calls, segments, tasks, voice_memos; CallDao, AppDatabase + migrations)
  inference/  Transcriber / Analyzer interfaces, model catalog + downloads;
              whisper/ (JNI, presets, quantization, expected words, second look at missed speech);
              speakers/ (loudness + voice per word, background speech);
              summaries (CallSummarizer, prompts, date checks), voice-note task prompt, Gemma
  processing/ foreground service with two parallel workers (transcribe, summary), request queue, log
  security/   optional extra encryption (Keystore-wrapped key, SQLCipher, sealed files)
  ui/         Compose UI (design system in Design.kt + theme/), settings pages, tasks, app lock
  util/       prefs, notifications, task reminders/actions, voice notes, retention, energy
app/src/main/java/com/k2fsa/sherpa/onnx/  sherpa-onnx Kotlin API (verbatim copy)
app/src/main/cpp/   JNI bridge to whisper.cpp (third_party/whisper.cpp submodule; local fixes in patches/)
scripts/fetch-sherpa-onnx.sh  prebuilt sherpa-onnx runtime for voice fingerprints (run before building)
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
- Review aids in CI: `screenshots` branch (rendered layouts per dev branch) and
  `reports` branch (Kotlin warnings + Android lint per dev branch).
- No dead code: remove what nothing calls (git history keeps it). The one exception is
  the verbatim sherpa-onnx API copy in `com/k2fsa` (see its README).
