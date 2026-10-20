# Capture layer

_Last verified: 2026-10-20 (code at 0.2)_

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
| `status` | `recorded` / `record_failed` (pipeline states added from 0.3) |

## Known gaps

- Whether the mic captures the far side without speaker is device-dependent. Yes on
  POCO X6 Pro / HyperOS 3; unknown elsewhere. If a device records the far side too
  quietly, the opt-in auto-speaker is the fix (whether `setCommunicationDevice` works
  from a non-dialer app is itself untested).
- The Google dialer's spoken "call is being recorded" notice only plays when its own
  recording is on; tell testers to switch it off.
- No foreground service; if a device kills the accessibility service process
  mid-call, the recording is lost. Watch for this on HyperOS.
