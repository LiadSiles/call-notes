# What the app stores, and where

_Last verified: 2026-10-20 (code at 0.3.0)_

Recordings, transcripts and settings live in the app's private internal storage
(`/data/data/com.callnotes/`). Model files live in the app-specific external dir
(`/storage/emulated/0/Android/data/com.callnotes/files/models/`) because
DownloadManager can't write to internal storage; on Android 11+ other apps can't read
it. Both are removed on uninstall, nothing is backed up (`allowBackup="false"`), and
nothing is written to shared storage.

| What | Path (under the app dir) | Written by | Size |
|---|---|---|---|
| Call recordings | `files/recordings/call_<startMillis>.wav` | `WavRecorder` | ~1.9 MB/min (16 kHz mono PCM16) |
| Compressed recordings (opt-in, after transcription) | `files/recordings/call_<startMillis>.m4a` | `AudioCompressor` | ~0.36 MB/min (AAC 48 kbps) |
| Call metadata | `databases/callnotes.db` (+ `-wal`, `-shm`) | Room | small |
| Imported audio | `files/recordings/import_<millis>.<ext>` | `Importer` | as imported |
| Transcripts (with speaker per line) | `databases/callnotes.db`, table `segments` | `TranscriptionService` | small |
| Summaries | `databases/callnotes.db`, `calls.analysisJson`, `calls.summaryTitle` | `TranscriptionService` | small |
| Tasks (+ source lines, certain, createdAt — DB v7) | `databases/callnotes.db`, table `tasks` | `SummaryWorker`, UI (done) | small |
| Temp audio while transcribing | `cache/convert_<id>.wav`, `cache/chunk.wav` | pipeline | deleted after use |
| LiteRT-LM cache | `cache/` | LiteRT-LM | engine-managed |
| Model | `Android/data/com.callnotes/files/models/gemma-4-E*-it.litertlm` (+ `.part` while downloading) | `ModelManager` | 2.6–3.7 GB |
| Transcription log | `files/transcription.log` (timings, memory, errors — no transcript text) | `ProcessingLog` | ≤ 64 KB, trimmed |
| Faster Whisper copies (only if a faster preset was used) | `Android/data/com.callnotes/files/models/<whisper>.q8_0.bin`, `.q5_0.bin` | `WhisperSpeed.prepare` | ~0.87 / ~0.57 GB |
| Task reminders / repeats (DB v8) | `tasks.remindedAt`, `tasks.duplicateOf` | `TaskReminders`, `SummaryWorker` | small |
| Owner's voice fingerprint (no audio kept) | `files/voice_profile.bin` | `VoiceProfile` | ~1 KB |
| Voice-fingerprint model | `Android/data/com.callnotes/files/models/wespeaker-en-voxceleb-resnet34-lm.onnx` | `ModelManager` | 26.5 MB |
| Settings | `shared_prefs/prefs.xml` | `Prefs` | tiny |

Database rows (`calls`): times, direction, number + cached name (only with call-log
permission), file path, capture diagnostics, status, kind. No transcript or summary yet.

Leaving the device: only when the user taps Share — `FileProvider`
(`${applicationId}.files`, `files/recordings/`) hands the chosen app a temporary
read grant for that one file.

Network: the only network use is the model download (DownloadManager, Hugging Face).

## Retention (0.8.0)

Off by default. Settings → פרטיות: recordings of transcribed calls after 1/7/30/90/365
days (`calls.filePath` → null, `calls.audioDeletedAt` set — DB v9; text kept), whole calls
after 30/90/180/365/730 days (skips calls with an open task). `util/Retention.kt`.

## Planned

- 0.9: optional extra encryption — SQLCipher database and encrypted recordings with a
  Keystore-wrapped key.
