# Capture layer

_Last verified: 2026-10-19 (code at 0.1)_

The app picks a capture path per device. Both produce the same thing — an audio file
plus call metadata — and the pipeline downstream is shared.

## Path A — self-recording with speakerphone (built, 0.1)

For phones with Google's dialer (Xiaomi/POCO global ROMs, Pixel, Motorola, stock-ish
Android). Flow, all in `capture/CallRecorderService.kt`:

1. `CallStateMonitor` turns telephony states into events.
   IDLE→OFFHOOK = outgoing (recording starts at dialing), RINGING→OFFHOOK = answered
   incoming, RINGING→IDLE = missed (no events). Needs `READ_PHONE_STATE` (API 31+
   `TelephonyCallback`). The service retries registration on every accessibility event,
   so granting the permission later works without restarting the service.
2. `WavRecorder` records 16 kHz mono PCM16 straight to WAV
   (`filesDir/recordings/call_<startMillis>.wav`). Source order:
   `VOICE_RECOGNITION` (less AGC/noise suppression → far side from the speaker survives
   better), then `MIC`. Recording runs inside the accessibility service — that UID is
   what the capture policy exempts.
3. Speakerphone, verified at 1.0 / 2.2 / 3.8 / 6.0 s:
   - `SpeakerController.requestSpeaker()` — `setCommunicationDevice(builtin speaker)`
     on API 31+, `isSpeakerphoneOn` before. Success is read back from the actual route,
     never assumed. Restored after the call if we changed it.
   - If still off and the setting is on: `SpeakerButtonTapper` clicks a node labelled
     רמקול / דיבורית / Speaker — **only inside the default dialer's windows**
     (`TelecomManager.defaultDialerPackage`), skipping if already checked.
   - At the last check: notification asking the user to turn it on, then a 3 s poll to
     record if the user did ("user").
   - Skipped entirely when a wired/USB/Bluetooth headset is connected.
4. On IDLE: stop, wait 2 s for the call log, then `CallLogResolver` matches the entry
   (DATE within [start−90 s, end]) for number, name, direction and connected duration.
   Unanswered outgoing calls (duration 0) are deleted.

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
| `speakerMethod` | `api` / `tap` / `user` / null |
| `status` | `recorded` / `record_failed` (pipeline states added from 0.3) |

## Known gaps

- Whether `setCommunicationDevice` from a non-dialer app moves a cellular call to the
  speaker is device-dependent — that's the main 0.1 test question.
- The Google dialer's spoken "call is being recorded" notice only plays when its own
  recording is on; tell testers to switch it off.
- No foreground service; if a device kills the accessibility service process
  mid-call, the recording is lost. Watch for this on HyperOS.
