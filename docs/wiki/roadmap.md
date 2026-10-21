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
| 0.3.7 | Cancel a call's transcription (→ held), stop all (list + notification, turns auto off), undo for every setup step / permission, restore default switches | ✅ built, awaiting device test |
| 0.3.8 | Gemma's documented ASR prompt (text before audio, no system instruction), per-chunk level normalization, recording SHA-256 before/after in the log | ✅ built, awaiting device test |
| 0.4.0 | Transcription → ivrit.ai Hebrew Whisper large-v3-turbo on whisper.cpp (JNI, on-device); models per role (transcribe / analyze) | ✅ built (lib verified in APK), awaiting device test |
| 0.4.1 | Who said what: sherpa-onnx (pyannote seg-3.0 + WeSpeaker ResNet34-LM), exactly 2 speakers, word-level alignment; owner voice sample → "me" / contact name; chat view; DB v5 | ✅ built, awaiting device test |
| 0.5.0 | Summary + tasks with Gemma 4 (LiteRT-LM, text) over the speaker-labelled transcript; tasks table with tick-off; summary title in the list; DB v6 | ⚠️ POCO: Whisper transcription ran, then the app crashed in speaker separation |
| 0.5.1 | Fix: diarization without sherpa's JNI progress callback (invokedynamic lambda → NoSuchMethodError → ART abort) | ✅ no crash; but handset call → everything one speaker |
| 0.5.2 | Speakers by loudness for handset calls (per-word dBFS, 2-means, Viterbi); voice diarizer as fallback on equalised audio | ✅ built, awaiting device test |
| 0.5.3 | Drop sherpa's diarization pipeline (exit(-1) killed the app); voice fallback = own clustering of fingerprinted Whisper word windows; pyannote no longer downloaded | ✅ built, awaiting device test |
| 0.5.4 | Summary JSON: Hebrew acronyms with ASCII quotes (נתב"ג) broke parsing → prompt asks for ״, parser repairs | ✅ built, awaiting device test |
| 0.5.5 | Transcription and summaries in parallel (two workers); a notification per operation naming the call (recording, transcribing, summarising) with Cancel / Stop all, tap opens the call; instant cancel (Whisper abort, Gemma streaming + cancelProcess) | ✅ built, awaiting device test |
| 0.6.0 | Tasks tab (all calls, open first, checkbox, jump to source line); notification per new task → tasks tab; summaries cite numbered transcript lines + "certain", verified in code ("לא בטוח" pill); every item jumps to its line; speaker fixes (long-press, swap); handset calls: loudness overrides voice sample; pause in battery saver; DB v7 | ✅ built, awaiting device test |
| 0.6.x | Tasks: reminders at due date, calendar export, duplicate detection across calls | |
| 0.6+ | Model swapping UI: reprocess, comparison screen, quantized Whisper | |
| 0.7 | Path B adapters: Samsung, OnePlus/Oppo/Realme, Vivo | |
| 0.8 | Privacy + reliability: SQLCipher, biometric lock, retention, audio compression, FTS search | |
| 0.9 | Closed beta (5–10 users), in-app updates, end-to-end test call in setup | |
| 1.0 | Release-signed build, polish, privacy policy, legal review | |

**After 1.0:** VoIP calling mode, names for internet calls, diarization, Bluetooth accessory.

## Waiting on

- Owner's verdict on Hebrew Whisper (0.4.0), speaker separation (0.4.1), summaries (0.5.0).
- Owner's verdict on 0.3.8 Hebrew quality (superseded by Whisper). If still poor → decide on a Hebrew Whisper
  transcriber (ivrit.ai `whisper-large-v3-turbo-ggml`, Apache-2.0, whisper.cpp) with
  Gemma kept for summaries.
- "Transcribed recordings sound worse": code only reads the file; the 0.3.8 log shows
  the hash before/after. Suspects if unchanged: playback while the phone is hot
  (speaker thermal limiting) or while the next call is being transcribed.

- 0.3.7 on the POCO: does "transcribe now" start, how long the model load takes, and
  the transcription log if anything stops (it shows the stage and free RAM).

- Owner can't make calls right now. Test 0.3 with imported audio: model download,
  transcription quality in Hebrew, speed, memory/heat.
- A trace from a call with silent stretches (0.3.1) → decides the gap fix.
- Still untested on the POCO: reboot survival, Bluetooth headset.

## Next

Device test of the 4-stage pipeline (0.4.0–0.5.0): Hebrew quality, speed per minute of
audio, speaker accuracy, summary quality, memory. Then: tasks list across calls,
reminders, calendar export (0.6); quantized Whisper if speed demands it.
