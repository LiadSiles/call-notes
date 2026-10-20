# What the app stores, and where

_Last verified: 2026-10-20 (code at 0.2.3)_

Everything lives in the app's private internal storage
(`/data/data/com.callnotes/`, also reachable as `/data/user/0/com.callnotes/`).
Other apps and file managers can't read it, it's removed on uninstall, and it isn't
backed up (`allowBackup="false"`). Nothing is written to shared storage.

| What | Path (under the app dir) | Written by | Size |
|---|---|---|---|
| Call recordings | `files/recordings/call_<startMillis>.wav` | `WavRecorder` | ~1.9 MB/min (16 kHz mono PCM16) |
| Call metadata | `databases/callnotes.db` (+ `-wal`, `-shm`) | Room | small |
| Settings | `shared_prefs/prefs.xml` | `Prefs` | tiny |

Database rows (`calls`): times, direction, number + cached name (only with call-log
permission), file path, capture diagnostics, status, kind. No transcript or summary yet.

Leaving the device: only when the user taps Share — `FileProvider`
(`${applicationId}.files`, `files/recordings/`) hands the chosen app a temporary
read grant for that one file.

## Planned

- `files/models/` — downloaded model files (GBs), 0.3.
- Transcripts, summaries and tasks in the same database, 0.3–0.5.
- 0.8: SQLCipher encryption, retention policy (default: audio 30 days, text kept),
  compressed audio.
