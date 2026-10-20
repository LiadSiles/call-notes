# Capture layer

_Last verified: 2026-10-20 (code at 0.3.0)_

The app picks a capture path per device. Both produce the same thing — an audio file
plus call metadata — and the pipeline downstream is shared.

## Path A — self-recording from the mic (built, 0.1; minimal permissions 0.2)

For phones with Google's dialer (Xiaomi/POCO global ROMs, Pixel, Motorola, stock-ish
Android). Flow, all in `capture/CallRecorderService.kt`:

1. `CallStateMonitor` turns telephony states into events.
   IDLE→OFFHOOK = outgoing (recording starts at dialing), RINGING→OFFHOOK = answered
   incoming, RINGING→IDLE = missed (no events). Needs `READ_PHONE_STATE` (API 31+
   `TelephonyCallback`). The service subscribes to no accessibility events, so the UI
   calls `CallRecorderService.refreshFromUi()` on resume to start detection if the
   permission was granted after the service connected.
2. `WavRecorder` records 16 kHz mono PCM16 straight to WAV
   (`filesDir/recordings/call_<startMillis>.wav`). Source order:
   `VOICE_RECOGNITION` (less AGC/noise suppression → far side from the speaker survives
   better), then `MIC`. Recording runs inside the accessibility service — that UID is
   what the capture policy exempts.
3. Speakerphone — **opt-in, default off since 0.2** (the owner's POCO captures both
   sides without it). When on, verified at 1.0 / 2.2 / 3.8 / 6.0 s:
   - `SpeakerController.requestSpeaker()` — `setCommunicationDevice(builtin speaker)`
     on API 31+, `isSpeakerphoneOn` before. Success is read back from the actual route,
     never assumed. Restored after the call if we changed it.
   - At the last check: notification asking the user to turn it on, then a 3 s poll to
     record if the user did ("user").
   - Skipped entirely when a wired/USB/Bluetooth headset is connected.
   - The 0.1 fallback that tapped the dialer's speaker button was removed in 0.2 (it
     required screen-content access).
4. On IDLE: stop, wait 2 s for the call log, then `CallLogResolver` matches the entry
   (DATE within [start−90 s, end]) for number, cached name, direction and connected
   duration. Unanswered outgoing calls (duration 0) are deleted. All of this is
   **optional** — without `READ_CALL_LOG` the call is saved without number/name and
   unanswered outgoing calls are kept.

## Internet calls (VoIP) — experimental (0.2.1)

WhatsApp, Telegram, Meet etc. Toggle "שיחות אינטרנט (ניסיוני)", default on.

- Detection: `AudioModeMonitor` watches `AudioManager` mode — `MODE_IN_COMMUNICATION`
  = a VoIP call is active. `addOnModeChangedListener` on API 31+, 2 s polling below.
  No permission. Can't tell which app or who's on the line (names would need
  notification-listener access — deliberately not added yet).
- Capture: same `WavRecorder` inside the accessibility service. The capture policy
  treats `MODE_IN_COMMUNICATION` as a voice call, so the accessibility exemption
  applies.
- **Open question:** the VoIP app's echo canceller. When several apps capture, the
  highest-priority client's pre-processing applies to the shared input, so the AEC may
  strip the far side from our stream. Device-dependent; test per device.
- Sessions under 8 s are discarded (apps also enter this mode to play voice messages
  through the earpiece). No speaker flow (`speakerResult = "na"`), no call-log lookup;
  `incoming` is stored as false and the UI shows "אינטרנט".
- A phone call arriving during a VoIP session ends the VoIP session and starts a
  cellular one.
- Device finding (POCO, 0.2.1): far side audible; but **capture went silent when the
  screen was locked mid-call** → microphone foreground service added in 0.2.3 (below).

## Foreground service during recording (0.2.3)

`RecordingService` is a `foregroundServiceType="microphone"` service started at the
beginning of every recording (cellular and VoIP) and stopped at the end. The recorder
stays in `CallRecorderService`; the FGS only keeps the process in the foreground state
Android requires for mic access once the screen locks. It shows the ongoing
"מקליט את השיחה" notification (`Notifications.recording`) and holds a partial wake
lock (4 h cap). The system's own mic-in-use indicator is shown as usual.
Starting a mic FGS from the background can be refused on some Android versions;
failures are caught and recorded in the per-call `foreground` flag (UI chip
"ללא שירות חזית").

## Path B — OEM dialer recordings (planned, 0.7)

User enables "auto-record all calls" in the OEM dialer once; the app detects new files
via MediaStore (`addContentUriTrigger` WorkManager + 15-min sweep), matching by
`RELATIVE_PATH` + filename patterns, not hard-coded paths. Typical locations
(unverified until tested per device):

| OEM | Path |
|---|---|
| Samsung One UI | `Recordings/Call` |
| Xiaomi with Xiaomi dialer | `MIUI/sound_recorder/call_rec` |
| OnePlus / Oppo / Realme | `Music/Recordings/Call Recordings` |
| Vivo | `Recordings/Record/Call` |

## Per-call diagnostics (`CallEntity`)

Recorded so a single test call answers "does this device work?":

| Field | Meaning |
|---|---|
| `audioSource` | `voice_recognition` or `mic` |
| `peakLevel` | max abs sample; < ~330 (≈ −40 dBFS) shown as "התקבל שקט" |
| `silenced` | Android reported capture-policy silencing (`AudioRecordingConfiguration.isClientSilenced`) |
| `speakerResult` | `on` / `failed` / `skipped_headset` / `disabled` |
| `speakerMethod` | `api` / `user` / null (`tap` only in 0.1 data) |
| `status` | `recorded` (awaiting transcription) / `record_failed` / `transcribing` / `transcribed` / `transcribe_failed` |
| `kind` | `cellular` / `voip` / `import` (DB v2, migration 1→2 adds it with default `cellular`) |
| `foreground` | mic foreground service ran during the call (DB v3); false → capture may be silenced on screen lock |

## Known gaps

- Whether the mic captures the far side without speaker is device-dependent. Yes on
  POCO X6 Pro / HyperOS 3; unknown elsewhere. If a device records the far side too
  quietly, the opt-in auto-speaker is the fix (whether `setCommunicationDevice` works
  from a non-dialer app is itself untested).
- The Google dialer's spoken "call is being recorded" notice only plays when its own
  recording is on; tell testers to switch it off.
- If a device refuses the mic foreground service, capture may still go silent on lock;
  the `foreground` flag shows it.
