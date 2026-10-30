# Architecture (0.24.1)

How the app is put together: processes, components, the path of a call from the ring to
a task, and what happens when something fails. Last reviewed 2026-10-30 (full review
against 0.20.0, the last version whose summaries finished on the owner's phone).

## Principles that shape everything

- **Nothing leaves the phone.** Recording, transcription, speakers, summary and tasks
  all run on the device; the network is used only to download model files. No analytics,
  no crash reporting service — diagnostics go to a local log the owner can share by hand.
- **Never lose what was said.** The recording stays on the phone (compressed after
  transcription, deleted only by the retention setting the user chose); a failed or
  interrupted step leaves the call in a state it can be retried from; Whisper's skipped
  speech gets a second look.
- **Minimal, revocable permissions; a cancel for everything.** Every queued or running
  item can be cancelled; "עצירת הכל" stops everything without touching the settings.
- **One process.** Recording, processing and UI share one process (a separate recording
  process was tried in 0.22.0 and reverted — see decisions.md).

## Components

```
                 ┌──────────────────── one app process ─────────────────────┐
 phone call ──►  │ capture/  CallRecorderService (accessibility)            │
                 │           ├ CallStateMonitor / AudioModeMonitor          │
                 │           ├ RecordingService (microphone FGS) + WavRecorder│
                 │           └ CallLogResolver, SpeakerController, CallTrace │
 shared audio ─► │ ui/ShareActivity ─► ui/Importer                          │
                 │                 │                                         │
                 │                 ▼                                         │
                 │ data/  Room: calls · segments · tasks · voice_memos (v12) │
                 │                 │                                         │
                 │                 ▼                                         │
                 │ processing/ TranscriptionService (dataSync/mediaProcessing FGS)
                 │   ├ TranscribeWorker ─► inference/whisper (whisper.cpp JNI)│
                 │   │                    ─► inference/speakers (sherpa-onnx) │
                 │   ├ SummaryWorker    ─► inference/GemmaAnalyzer (LiteRT-LM, GPU→CPU)
                 │   │                    ─► inference/CallSummarizer + prompts
                 │   ├ ProcessingStatus (request queue, live state for the UI)
                 │   ├ MemoryGuard, ProcessingLog                             │
                 │                 │                                         │
                 │                 ▼                                         │
                 │ ui/ (Compose)  ·  util/Notifications, TaskReminders       │
                 └──────────────────────────────────────────────────────────┘
```

| Layer | Package | Responsibility |
|---|---|---|
| Capture | `capture/` | Detect calls (cellular via telephony state, internet calls via audio mode), record the mic from the accessibility service during the call, name the call from the call log, keep a per-call trace. See [capture.md](capture.md). |
| Import | `ui/ShareActivity`, `ui/Importer` | Audio shared from WhatsApp or any app (or picked in the app) is copied in as a call of kind `import` and queued for transcription *and* summary. |
| Data | `data/` | Room database (optionally SQLCipher), DAO with every query the app uses, migrations. See [storage.md](storage.md). |
| Processing | `processing/` | A foreground service with two workers running side by side: transcription and summary. Queue, status, notifications, log, crash/kill recovery. |
| Models | `inference/` | Swappable `Transcriber` / `Analyzer` interfaces (`Models.kt`); Whisper, speakers, Gemma, prompts, parsing and checks — pure Kotlin where possible, unit-tested. See [models.md](models.md). |
| Tasks | `util/TaskReminders`, `TaskActions`, `VoiceMemos`, `ui/Tasks*` | Tasks from calls, typed, or spoken; reminders and snooze. See [tasks.md](tasks.md). |
| Security | `security/` | Optional extra encryption: Keystore-wrapped key, SQLCipher, sealed recording files. |
| UI | `ui/` | Three tabs (calls, tasks, settings), the call screen, settings pages, the design system ([design.md](design.md)). |
| Support | `util/` | Settings (`Prefs`), energy and battery-saver rules, retention, storage page, notifications. |

## The path of a call

1. **Ring → recording.** `CallStateMonitor` (or `AudioModeMonitor` for internet calls)
   tells `CallRecorderService` a call started; it starts `RecordingService` (microphone
   foreground service) which writes a WAV. Only an accessibility service gets call audio
   on Android — the reason the whole capture layer lives there.
2. **Hang-up → row.** The call is inserted with status `recorded`, its contact/number,
   kind (`cellular`/`voip`) and diagnostics; `TranscriptionService.start(afterCall)`.
3. **Transcription** (`TranscribeWorker`): decode to 16 kHz WAV → Whisper (ivrit.ai
   large-v3-turbo; preset accurate/fast) → second look at missed speech → speakers
   (loudness + voice per word, "me") → background speech set aside → segments saved,
   status `transcribed`, recording compressed to AAC.
4. **Summary** (`SummaryWorker`): Gemma 4 E4B, "precise" = three questions per part
   (about, commitments with quotes, review); long calls in parts and merged; dates checked
   against the words said; tasks de-duplicated across calls; tasks saved and announced
   (only the owner's; the other side's commitments shown in the call as "ממתין מ…").
5. **Tasks** appear in the tasks tab; reminders are planned.

Statuses: `calls.status` = recorded → transcribing → transcribed (or record_failed /
transcribe_failed / held); `calls.analysisStatus` = null (not yet) → done / failed / skipped.

## Processing: queue, workers, threads

- **Who starts work:** a finished call, the app opening, the charger, battery saver
  ending, a user request (transcribe / summarise / import / voice note). Automatic work
  follows the switches (auto transcription, auto summary, "only while charging", length
  limit); user requests always run.
- **Queue** (`ProcessingStatus.queue`): user requests in the order asked; one item at a
  time per worker; nothing in progress is interrupted by a new request; automatic work
  comes after requests. One notification row per item in progress or waiting.
- **Two workers in parallel:** while Whisper transcribes call N, Gemma can summarise call
  N−1. Before loading Gemma next to Whisper the summary worker waits for free memory.
- **Threads:** worker loops, Whisper and speakers run on `InferenceThreads` (6 threads,
  nice +5, so scrolling stays smooth). Gemma runs on `Dispatchers.IO` exactly as in 0.20.0
  (see decisions.md, 0.24.1).
- **Foreground service:** `dataSync|mediaProcessing`; `mediaProcessing` has a daily time
  limit on Android 15+, after which `dataSync` is used. Partial wake lock while working.

## Failure handling

| What | Handling |
|---|---|
| Process dies mid-transcription (crash, memory, kill) | Next start: the call → `transcribe_failed` with the reason; the user retries. One bad file can't crash every start. After a cleanup (recent apps cleared, Security app) it goes back to waiting instead. |
| Process dies mid-summary | Next start: a cleanup (recent apps cleared, Security app) → the summary just runs again; otherwise one more try, then `failed` (no automatic retry loop). |
| Gemma passes HyperOS's 6 GB memory line | `MemoryGuard` stops the summary just before it (a force stop would also turn accessibility off); the call shows why. |
| GPU can't open Gemma | CPU, logged ("GPU not usable: …"). |
| Process dies while Gemma is on the GPU (native crash or memory) | The "סיכום במעבד הגרפי" switch turns itself off; summaries use the CPU. |
| Whisper makes no progress for 4 minutes | That run stops; the call is read again greedy with no temperature retries. |
| "עצירת הכל" | Cancels what runs (not retried automatically), empties the queue; switches untouched. |
| Accessibility on but service not running | Settings show "on but not running" with the fix; the process exit reason is in the log. |
| Any crash in app code | Written to the log with the stack before Android handles it. |

## Diagnostics

`files/transcription.log` (64 KB, newest kept), shared as a file from Settings →
תמלול → יומן עיבוד. Holds timings, memory, battery, decisions, exit reasons, switch
changes — never transcript text. Every step is also the persisted "stage", which is how
the next start knows where a dead process stopped. Per-call chips and a per-call trace
cover the recording side.

## Build and distribution

Sideloaded APK from GitHub Releases (Play bans accessibility call recording). Each push to
`main` builds; the published APK is the debug build (0.22.1), the R8 build is attached
as `-optimized-test`. CI also renders screenshots and publishes Kotlin warnings and lint
per branch. See [build-and-release.md](build-and-release.md).
