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
| Call metadata | `databases/callnotes.db` (+ `-wal`, `-shm`) | Room | small |
| Imported audio | `files/recordings/import_<millis>.<ext>` | `Importer` | as imported |
| Transcripts | `databases/callnotes.db`, table `segments` | `TranscriptionService` | small |
| Temp audio while transcribing | `cache/convert_<id>.wav`, `cache/chunk.wav` | pipeline | deleted after use |
| LiteRT-LM cache | `cache/` | LiteRT-LM | engine-managed |
| Model | `Android/data/com.callnotes/files/models/gemma-4-E*-it.litertlm` (+ `.part` while downloading) | `ModelManager` | 2.6–3.7 GB |
| Settings | `shared_prefs/prefs.xml` | `Prefs` | tiny |

Database rows (`calls`): times, direction, number + cached name (only with call-log
permission), file path, capture diagnostics, status, kind. No transcript or summary yet.

Leaving the device: only when the user taps Share — `FileProvider`
(`${applicationId}.files`, `files/recordings/`) hands the chosen app a temporary
read grant for that one file.

Network: the only network use is the model download (DownloadManager, Hugging Face).

## Planned

- Summaries and tasks in the same database, 0.4–0.5.
- 0.8: SQLCipher encryption, retention policy (default: audio 30 days, text kept),
  compressed audio.
