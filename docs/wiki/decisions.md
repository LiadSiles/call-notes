# Decisions

Newest first. Each entry: what was decided, why, and what was rejected. Add an entry
whenever a choice would surprise a future contributor.

### 2026-10-22 — Extra encryption is opt-in and reversible (0.9.0)
Android's file-based encryption already protects app storage at rest with the lock
screen; app-level encryption adds protection for a copied data folder, a rooted phone or
a forensic image of an unlocked one — at the cost of losing everything if the Keystore key
is ever wiped. So it's a switch, off by default, with that risk stated in the confirmation.
Design: one random 256-bit data key, stored only wrapped (AES-GCM) by a non-exportable
Android Keystore key (`security/KeyVault`). Database: SQLCipher with the raw key
(`x'…'`, no PBKDF2 delay), converted with `sqlcipher_export` before Room opens it, so a
change takes effect at the next app start (settings offer a restart through a tiny
`:restart`-process activity, enabled only when nothing records or processes). Recordings:
"CNE1" + IV + AES-256-CTR, sealed after transcription (and compression), so a call in
progress or waiting for transcription is still a plain file in private storage; CTR lets
`MediaDataSource` decrypt any position for the player; transcription and sharing use a
temporary plain copy in `cache/plain|share`, cleared at app start. A database that won't
open with the key is renamed `*.unreadable-<time>`, sealed recordings deleted, the user
told — rather than a crash loop.

### 2026-10-22 — Retention is the owner's choice, off by default (0.8.0)
Owner: "auto delete will be determined by the user". Two independent settings: recordings
of transcribed calls after N days (text stays), whole calls after N days. Nothing is
deleted until chosen; picking a period that already covers existing items shows the
count and asks first. Calls with an open task are kept (deleting them would silently drop
a to-do). Untranscribed recordings are never deleted by the recordings rule. Runs daily
(inexact alarm), at app start and on change.

### 2026-10-22 — App lock is optional and covers what others can see (0.8.0)
Off by default. On: BiometricPrompt (weak biometric or the phone's PIN/pattern) when the
app opens or returns after 30 s, `FLAG_SECURE` (no screenshots, blank recents preview),
and task / processing notifications become `VISIBILITY_PRIVATE` with a generic public
version. Recording and processing are unaffected. Turning it on or off asks for the lock
first. New install-time permissions, no prompts: `USE_BIOMETRIC` (from androidx.biometric),
and `RECEIVE_BOOT_COMPLETED` (0.7.0, re-plans task reminders).

### 2026-10-22 — Search is a plain substring match (0.8.0)
Hebrew attaches prefixes (ו ה ב ל מ ש כ) to words, so FTS tokens miss "מחיר" in "המחיר";
a `LIKE '%q%'` over a few thousand transcript lines is instant and finds both. FTS can
come later if libraries grow large.

### 2026-10-22 — Compression is opt-in (0.8.0)
The owner once reported recordings sounding worse after transcription (a bug, fixed), so
lossy re-encoding is never applied silently: AAC 48 kbps mono, only after the transcript
is saved, the WAV removed only after the .m4a's duration matches.

### 2026-10-22 — Calendar = Google Calendar, through its own editor (0.7.0)
Owner chose Google Calendar. The app opens Calendar's prefilled "new event" screen
(`ACTION_INSERT`, package `com.google.android.calendar`, any calendar app as fallback)
instead of writing events itself: no calendar permission, and nothing leaves the app
until the user taps Save there. Only the task title, the due phrase and the call label go
in — not the quote. Google Calendar syncs to the user's Google account; that's the user's
explicit, per-task choice.

### 2026-10-22 — Repeated tasks: strict match, original keeps the identity (0.7.0)
A task in a new call that matches an open task from an earlier call (last 60 days) is
stored with `duplicateOf` = that task: shown once (under the original, with "came up
again" links), not announced again, no second reminder, ticking either ticks both.
Matching (`TaskMatcher`) is deliberately strict — ≥ 75 % of the shorter title's content
words (Hebrew prefixes folded), same owner and no conflicting due dates — because a
wrong merge hides a task while a missed one only shows it twice. Undo: "לא אותה משימה".

### 2026-10-22 — Faster Whisper by quantizing on the phone (0.7.0)
No quantized ivrit.ai build is published, and the app must not use a server. The app
writes q8_0 / q5_0 copies of the downloaded f16 model itself, with a streamed port of
whisper.cpp's `quantize` (same tensor rules, row chunks → low memory, progress, cancel).
The copy is made once, the first time a faster preset transcribes; the f16 file is kept
so "accurate" stays one tap away. Settings show measured speed per preset on this phone
(× call length) rather than promised numbers.

### 2026-10-21 — Summaries must show their evidence (0.6.0)
Owner: summaries, key points and tasks must be very accurate, and anything uncertain must
be marked. Rather than trust the model's self-assessment alone, every item cites
transcript line numbers and a `certain` flag, and `AnalysisPrompt.parse` downgrades to
uncertain anything it can't verify: cited lines out of range or missing, a task quote not
found in its lines (normalised, ≥ 75 % of words), a due date it had to drop. The cited
line doubles as the link from the item to the transcript. Pre-0.6 summaries (plain
strings) still load, as uncited points.

### 2026-10-21 — Speaker labels: physics first, user has the last word (0.6.0)
At a ≥ 15 dB level gap the phone was at the owner's ear; louder = owner, and the voice
sample (compared against a distorted earpiece leak) may not overrule it. Users fix the
rest: long-press a line (this line is me / them), or swap the whole call
(`calls.meSpeaker`). The summary then offers to re-run.

### 2026-10-21 — Two parallel workers, instant cancel (0.5.5)
Owner wants transcription and summaries at the same time, a temporary notification per
operation naming the call, and cancel that acts at once. `TranscriptionService` now hosts
`TranscribeWorker` and `SummaryWorker` (`processing/Worker.kt` base): own model, queue,
`ProcessingStatus.state` / `.summary`, notification (`ID_TRANSCRIBE` / `ID_SUMMARY`).
The service is in the foreground with whichever notification is live (re-anchored when one
finishes) and stops when both are idle. Memory: Gemma's weights are mmapped; before
loading it next to Whisper the summary worker waits while the phone reports low memory
(`availMem` < 1.5 GB). Cancel updates UI state + DB + notification synchronously and then
aborts the engine (whisper.cpp abort callback; LiteRT-LM `sendMessageAsync` +
`Conversation.cancelProcess()`); results arriving for a cancelled call are dropped.
"Stop all" returns the running calls to the queue and turns automatic processing off.

### 2026-10-21 — No third-party native pipeline that can exit the process (0.5.3)
sherpa-onnx's diarization pipeline aborts with `exit(-1)` on inputs it dislikes; in an
app that's indistinguishable from a crash and can't be caught. We keep only its
smallest, well-defined piece (fingerprint extraction), validate its inputs in Kotlin,
and do segmentation/clustering ourselves on top of Whisper's word timing (pure Kotlin,
unit-tested). Rule for future native dependencies: grep their sources for
`exit(`/`abort(` on reachable paths before adopting them.

### 2026-10-21 — Loudness first, voice embeddings second, for who-said-what (0.5.2)
With one microphone at the owner's mouth, the strongest cue in a handset call is the
level gap between the owner and the earpiece leak; general-purpose diarization
(pyannote) is built for comparable voices and failed on the POCO (one speaker). So the
level method runs first and the voice method only when levels are close. Rejected:
lowering the merge threshold (would still split the owner's voice in two), requiring
speakerphone (the owner holds the phone to the ear).

### 2026-10-21 — Hebrew Whisper + local diarization; Gemma for understanding (0.4)
Owner: transcripts must say who said what, everything strictly on-device ("the whole
point of the app is to guarantee privacy" — no cloud, no internet services; network
only to download models). Chosen pipeline:
1. **Transcription:** ivrit.ai Hebrew fine-tune of Whisper large-v3-turbo, ggml for
   whisper.cpp. Their Interspeech 2025 paper (large-v2 fine-tune) reports 6.2 % WER on
   two-person conversation vs 8–9.6 % for OpenAI Whisper and 21 % for Google Speech.
2. **Speakers:** pyannote segmentation-3.0 + a speaker-embedding model, clustered into
   exactly 2 (a phone call). Voice-based, language-independent.
3. **Who is "me":** a one-time voice sample of the owner; nearest cluster = me.
4. **Understanding:** Gemma 4 reads the labelled transcript → summary, tasks.
Rejected: Gemma audio for ASR (poor Hebrew, no published Hebrew numbers); cloud ASR
(AWS/ElevenLabs/Soniox — excellent Hebrew but violates the privacy rule); wav2vec2 /
small Hebrew models (faster, less accurate — maybe a "light mode" later).
Supersedes "Gemma 4 for both transcription and analysis" (2026-10-18).

### 2026-10-21 — Gemma ASR on its documented template (0.3.8)
Owner found 0.3.x Hebrew transcripts mostly unusable. First fix stays within Gemma:
the trained ASR prompt, instruction before audio, no system instruction, normalized
input level. Next option if that's not enough: a dedicated Hebrew ASR model —
ivrit.ai's Hebrew fine-tune of Whisper large-v3-turbo has a ggml build for whisper.cpp
(Apache-2.0). That fits the existing `Transcriber` interface; Gemma would stay the
Analyzer. Owner decides (it changes the "Gemma only" choice of 2026-10-18).

### 2026-10-21 — Every started action can be undone (0.3.7)
Owner rule: anything the user starts needs a visible way back.
- Cancel one call → status `held`: skipped by automatic runs (`nextPending` only takes
  `recorded`), manual "transcribe now" still works. A cancelled re-transcription goes
  back to `transcribed`: old segments are only replaced when the new transcript is
  complete (`CallDao.revertTranscription`). Status changes during transcription use
  `setStatusOnly` so the old transcript's model name survives.
- Stop all (list panel, notification action `ACTION_STOP`) → cancels the run and turns
  `autoTranscribe` off; otherwise the next app open would restart it at once. The list
  shows "automatic transcription is off · N waiting · turn back on".
- The engine is closed only after a cancelled chunk's native call returns (`join`), never
  under it.
- Setup steps and optional permissions show their reverse once done. Android lets an app
  request a permission or exemption but not give it back, so "undo" opens the system
  screen (app details, accessibility, battery-optimisation list). "Restore defaults"
  resets the switches only — never model, permissions or data.

### 2026-10-21 — Transcription queue order, feedback and log (0.3.5)
Bug seen on the POCO: with a model installed, opening the app starts an automatic run
over every pending recording (newest first). "Transcribe now" on another call only
woke the running service, so the tapped call stayed "waiting" with no visible change.
Now `TranscriptionService.start(callId=…)` puts the call in `ProcessingStatus.requested`
(the UI shows "starting" at once); the service takes requests first and cancels an
automatic run on a different call between chunks (that call returns to `recorded` —
the reset runs under `NonCancellable`, before it was silently skipped). The model load
is an explicit phase (`Transcriber.load()`) shown with elapsed time.
`ProcessingLog` writes `files/transcription.log` (timings, free RAM, chunk counts,
exceptions with top frames; never transcript text) and persists the current stage
synchronously in prefs. `CallNotesApp.onCreate` treats a leftover stage as a run the
system killed (OOM / native crash): logs it, notes it, and fails the stuck call with
that stage so it gets a retry button. Rejected for now: a separate `:transcribe`
process (would survive a native crash but needs cross-process status + Room
multi-instance invalidation) — revisit if the log shows engine crashes.

### 2026-10-21 — Navigation and list state (0.3.4)
The call list is a process-wide `StateFlow` (`CallNotesApp.callList`, `stateIn` Eagerly,
null = not loaded yet) so returning to the list never renders an empty frame. List ↔
detail is one `AnimatedContent` (slide + fade); tabs crossfade; the window background
(`values[-night]/themes.xml`) matches the Compose theme. Manual "transcribe now" calls
`TranscriptionService.start` directly — previously it went through the auto-transcribe
gate and did nothing when that switch was off.

### 2026-10-21 — Active model = installed model only (0.3.2)
Bug: tapping "switch to E2B" set the active id to a model that wasn't downloaded, so
`readyModel()` returned null and transcription silently never ran. Now the active
model only changes to an installed one, or after a user-started download verifies;
an uninstalled saved choice falls back to any installed model. Each transcription run
writes a one-line outcome (`Prefs.lastProcessing`) shown in the transcription card.

### 2026-10-21 — UI redesign (0.3.3)
Fixed "ink on paper" palette (green brand, warm neutrals, amber for in-progress) instead
of dynamic colour; bottom navigation with two tabs; call list grouped by day with the
first transcript line; a call detail screen (player, transcript that highlights the
playing segment and seeks on tap, technical details folded away). The settings tab
reuses the 0.3.x setup/permission/switch sections unchanged (`ui/SettingsSections.kt`,
only the containers restyled) — writing a new settings screen was stopped by a safety
filter in the session, so the existing, already-shipped code was moved instead.

### 2026-10-20 — 0.3 transcription design choices
- **Model download via DownloadManager** into the app-specific external dir (resumes,
  system progress notification, Wi-Fi-only by default), then SHA-256 check before use.
  `INTERNET` permission added for this only.
- **CPU backend**, greedy decoding, thinking off, one conversation per 28 s chunk.
- **Kotlin 2.4** because LiteRT-LM 0.17.1 ships Kotlin 2.4 metadata.
- **Audio import** (any format) so the pipeline can be tested without calls; imports
  get `kind = "import"`.
- **No crash loops:** a call left in `transcribing` at the start of a run is marked
  failed (not retried), since a native crash/OOM would otherwise repeat every launch.
- Transcription starts from `finish()` while the mic foreground service is still up
  (background FGS starts can be refused); the UI also kicks the queue on resume.
- Owner chose to continue development while unable to make test calls; the 0.2.3
  screen-lock fix is untested.

### 2026-10-20 — Record inside a microphone foreground service
Capture went silent when the screen locked during a call. The standard, visible fix:
a `microphone`-type foreground service for the length of each recording, with an
ongoing notification. Chosen over any approach that would hide the recording from the
user; the notification and the system mic indicator stay.

### 2026-10-20 — Record internet calls (experimental), no names yet
Owner asked for WhatsApp recording. Detected via audio mode (no permission), recorded
by the same accessibility-service capture. Names deferred: they'd need notification
listener access (reads all notifications) — owner chose "no names for now". Default
on; pulled forward from "after 1.0".

### 2026-10-20 — Minimal permissions (0.2)
The owner asked to drop everything recording doesn't use. The 0.1 test showed both
sides are captured without speakerphone, so:
- **Required:** accessibility service (with no event types and
  `canRetrieveWindowContent=false` — it can't read the screen), `RECORD_AUDIO`,
  `READ_PHONE_STATE`.
- **Optional:** `READ_CALL_LOG` (number, cached name, direction, unanswered-call
  cleanup), `POST_NOTIFICATIONS`. App records fine without them.
- **Removed:** `READ_CONTACTS` (the call log's cached name suffices) and the
  speaker-button tap (needed window content).
- **Kept, invisible to the user:** `MODIFY_AUDIO_SETTINGS` (normal permission) for an
  opt-in auto-speaker, default off, for devices whose far side turns out quiet.
- Since the service no longer receives events, the UI calls
  `CallRecorderService.refreshFromUi()` on resume to start call detection if
  `READ_PHONE_STATE` was granted after the service connected.
Possible further step: detect calls from `AudioManager` mode (`MODE_IN_CALL`, no
permission) instead of `READ_PHONE_STATE` — untested, not done.

### 2026-10-19 — Build APKs in GitHub Actions, install from Releases
Owner wants to install without developer options or Android Studio. CI builds every
push and publishes a prerelease. Side benefit: CI is the compile check for sessions
that can't download Android dependencies. Fixed debug keystore committed so updates
install in place.

### 2026-10-19 — Path A: accessibility service + automatic speakerphone
On Google-dialer phones there is no legitimate way to get both sides at full quality.
Chosen: record the mic from an accessibility service (the only exemption from
in-call mic silencing) and move the call to the loudspeaker (API → tap the in-call
speaker button → ask the user). Rejected: automating the dialer's Share flow (screen
takeover, owner rejected it), other system apps, fake Bluetooth, root. Deferred past
1.0: VoIP mode, Bluetooth accessory.

### 2026-10-19 — Owner's phone runs path A
POCO X6 Pro uses Google's dialer, whose recordings aren't in shared storage
(see devices.md). Path A is built first; path B moves to 0.7.

### 2026-10-19 — Skip the standalone Hebrew feasibility test
Measure inside the app instead: keep original audio, make models swappable, add a
side-by-side comparison screen. If Gemma's Hebrew transcription is weak, swap the
Transcriber (e.g. ivrit.ai Whisper) without touching the rest.

### 2026-10-19 — Separate, user-swappable Transcriber and Analyzer
The owner wants users to be able to change the analysis model. Two interfaces, a model
catalog, per-call record of which models ran.

### 2026-10-18 — Gemma 4 for both transcription and analysis; E4B with E2B fallback
Gemma 4 E2B/E4B are the only on-device Gemma models with audio input. Processing
happens after the call in the background, so latency matters less than quality →
E4B where RAM allows. 30 s audio limit → 28 s chunks with 3 s overlap.

### 2026-10-18 — Models downloaded at setup, not bundled
Multi-GB APKs are impractical, the model differs per device, and users can swap it.

### 2026-10-18 — Not AICore / Gemini Nano
Inference there is allowed only for the top foreground app; we need background
processing, and device coverage is limited.

### 2026-10-18 — Android only
iOS gives third-party apps no access to cellular call audio.
