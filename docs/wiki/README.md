# CallNotes wiki

Working knowledge for anyone (human or LLM) developing the app. Short, factual pages;
each one says when it was last verified. Update the page in the same commit as the
change that made it stale.

| Page | Read it when |
|---|---|
| [product.md](product.md) | You need the goal, scope and user-facing behaviour |
| [architecture.md](architecture.md) | You need the big picture: components, the path of a call, threads, failure handling |
| [android-call-audio.md](android-call-audio.md) | Touching anything about how a call gets recorded |
| [capture.md](capture.md) | Working in `capture/` — paths A and B, speaker logic, diagnostics |
| [storage.md](storage.md) | Anything about files, the database, retention or what leaves the device |
| [devices.md](devices.md) | Something behaves differently on a specific phone |
| [models.md](models.md) | Working on transcription, speakers, summarization or model management |
| [tasks.md](tasks.md) | Tasks from calls, typed or recorded; reminders and snooze |
| [design.md](design.md) | Building UI: the design system, components, screenshots |
| [build-and-release.md](build-and-release.md) | Building, signing, CI, installing on a phone |
| [decisions.md](decisions.md) | Before proposing an alternative — it may already be rejected |
| [roadmap.md](roadmap.md) | Picking the next piece of work; current status |

The owner's Hebrew planning document (claude.ai artifact "אפליקציית סיכום שיחות –
תכנון מקצה לקצה") is the product-level source; this wiki is the engineering one.

## UI map (0.24.1)

`ui/MainActivity.kt` (three tabs, notification taps, app lock) →
- **Calls:** `CallsScreen` (list, search via `SearchResults`, "ייבוא אודיו" → `Importer`) →
  `CallDetailScreen` (player, `SummarySection` with "which one is you?", transcript with
  background-speech toggle, speaker fixes).
- **Tasks:** `TasksScreen` (+ `TaskEditor`, `VoiceTask` record dialog and voice-note cards,
  `TaskExtras` edit/calendar row).
- **Settings:** `SettingsScreen` hub → pages built from `SettingsSections`,
  `TranscriptionSwitches`, `ModelSection`, `SpeedSection`, `SpeakerSection`,
  `PrivacySection` (+ `StorageSection`), `SetupChecks`.
- **Share target:** `ShareActivity` (no screen) — audio shared from WhatsApp or any app →
  `Importer` → queued for transcription and tasks.

Design system (`Design.kt`, `Components.kt`, `theme/`) — see [design.md](design.md).
