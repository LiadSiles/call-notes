# Roadmap and status

_Last updated: 2026-10-20_

| Version | Scope | Status |
|---|---|---|
| 0.1 | Capture layer: call detection, recording, auto speaker, storage, per-call diagnostics, setup checklist | ✅ tested on POCO: both sides clear, with or without speaker |
| 0.2 | Minimal permissions: drop contacts + screen access, call log/notifications optional, speaker opt-in | ✅ built |
| 0.2.1 | Internet-call recording (WhatsApp etc.), experimental, no names | ✅ built, awaiting device test |
| 0.2.2 | Seek bar + play/pause on each recording | ✅ built |
| 0.3 | Gemma: model download + per-device choice, chunked transcription + merge, background processing, filter the dialer's recording announcement | |
| 0.4 | Summary + tasks in the fixed schema, date normalization, full call screen (summary, transcript with seek, task approval) | |
| 0.5 | Tasks: in-app list, reminders, calendar export, duplicate detection | |
| 0.6 | Model swapping: catalog, Whisper + llama.cpp engines, reprocess, comparison screen | |
| 0.7 | Path B adapters: Samsung, OnePlus/Oppo/Realme, Vivo | |
| 0.8 | Privacy + reliability: SQLCipher, biometric lock, retention, audio compression, FTS search | |
| 0.9 | Closed beta (5–10 users), in-app updates, end-to-end test call in setup | |
| 1.0 | Release-signed build, polish, privacy policy, legal review | |

**After 1.0:** VoIP calling mode, names for internet calls, diarization, Bluetooth accessory.

## Waiting on

- Owner installs 0.2.1: phone call still records with reduced permissions; WhatsApp call — is the far side audible?
- Still untested on the POCO: reboot survival, Bluetooth headset.

## Next

0.3 — Gemma transcription.
