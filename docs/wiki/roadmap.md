# Roadmap and status

_Last updated: 2026-10-21_

| Version | Scope | Status |
|---|---|---|
| 0.1 | Capture layer: call detection, recording, auto speaker, storage, per-call diagnostics, setup checklist | ✅ tested on POCO: both sides clear, with or without speaker |
| 0.2 | Minimal permissions: drop contacts + screen access, call log/notifications optional, speaker opt-in | ✅ built |
| 0.2.1 | Internet-call recording (WhatsApp etc.), experimental, no names | ✅ works on POCO (far side audible) |
| 0.2.2 | Seek bar + play/pause on each recording | ✅ built |
| 0.2.3 | Microphone foreground service so recording survives screen lock | ✅ built, awaiting device test |
| 0.3 | Gemma: model download + per-device choice, chunked transcription + merge, background processing, filter the dialer's recording announcement, audio import | ✅ built (CI green), awaiting device test |
| 0.3.1 | Per-call diagnostic trace for recording gaps | ✅ built |
| 0.3.2 | Model-selection fix (active = installed only), per-model rows, last-run status | ✅ built |
| 0.3.3 | UI redesign: theme, bottom nav (calls / settings), grouped call list with transcript preview, call detail with player and live transcript, settings tab | ✅ built |
| 0.3.4 | Manual transcription independent of auto switch; animated navigation, no white flash | ✅ navigation OK; manual transcription still did nothing → 0.3.5 |
| 0.3.5 | Manual request jumps the queue (pauses an auto run), live state (model loading + elapsed, chunks), transcription log, recovery of runs killed mid-way | ⚠️ log showed the service never started (FGS type "none") |
| 0.3.6 | Fix: transcription service started with explicit mediaProcessing type (dataSync fallback) | ✅ built, awaiting device test |
| 0.4 | Summary + tasks in the fixed schema, date normalization, full call screen (summary, transcript with seek, task approval) | |
| 0.5 | Tasks: in-app list, reminders, calendar export, duplicate detection | |
| 0.6 | Model swapping: catalog, Whisper + llama.cpp engines, reprocess, comparison screen | |
| 0.7 | Path B adapters: Samsung, OnePlus/Oppo/Realme, Vivo | |
| 0.8 | Privacy + reliability: SQLCipher, biometric lock, retention, audio compression, FTS search | |
| 0.9 | Closed beta (5–10 users), in-app updates, end-to-end test call in setup | |
| 1.0 | Release-signed build, polish, privacy policy, legal review | |

**After 1.0:** VoIP calling mode, names for internet calls, diarization, Bluetooth accessory.

## Waiting on

- 0.3.6 on the POCO: does "transcribe now" start, how long the model load takes, and
  the transcription log if anything stops (it shows the stage and free RAM).

- Owner can't make calls right now. Test 0.3 with imported audio: model download,
  transcription quality in Hebrew, speed, memory/heat.
- A trace from a call with silent stretches (0.3.1) → decides the gap fix.
- Still untested on the POCO: reboot survival, Bluetooth headset.

## Next

0.4 — summary and tasks (Analyzer), full call screen.
