# Decisions

Newest first. Each entry: what was decided, why, and what was rejected. Add an entry
whenever a choice would surprise a future contributor.

### 2026-10-21 — Active model = installed model only (0.3.2)
Bug: tapping "switch to E2B" set the active id to a model that wasn't downloaded, so
`readyModel()` returned null and transcription silently never ran. Now the active
model only changes to an installed one, or after a user-started download verifies;
an uninstalled saved choice falls back to any installed model. Each transcription run
writes a one-line outcome (`Prefs.lastProcessing`) shown in the transcription card.

### 2026-10-21 — UI redesign parked on `dev/0.3.2`
Theme, bottom navigation, grouped call list with transcript preview and a call detail
screen are on `dev/0.3.2`. The new settings screen (setup checklist + toggles) could not
be written in the session that started it; that branch doesn't build until
`ui/SettingsScreen.kt` exists (signature: `SettingsScreen(contentPadding, resumeTick,
onModelReady)`, using `rememberSetupState`, `ModelSection`, `SetupChecks`). Main keeps
the 0.3.x single-screen UI.

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
