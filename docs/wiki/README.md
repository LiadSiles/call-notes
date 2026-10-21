# CallNotes wiki

Working knowledge for anyone (human or LLM) developing the app. Short, factual pages;
each one says when it was last verified. Update the page in the same commit as the
change that made it stale.

| Page | Read it when |
|---|---|
| [product.md](product.md) | You need the goal, scope and user-facing behaviour |
| [android-call-audio.md](android-call-audio.md) | Touching anything about how a call gets recorded |
| [capture.md](capture.md) | Working in `capture/` — paths A and B, speaker logic, diagnostics |
| [storage.md](storage.md) | Anything about files, the database, retention or what leaves the device |
| [devices.md](devices.md) | Something behaves differently on a specific phone |
| [models.md](models.md) | Working on transcription, summarization or model management |
| [build-and-release.md](build-and-release.md) | Building, signing, CI, installing on a phone |
| [decisions.md](decisions.md) | Before proposing an alternative — it may already be rejected |
| [roadmap.md](roadmap.md) | Picking the next piece of work; current status |

The owner's Hebrew planning document (claude.ai artifact "אפליקציית סיכום שיחות –
תכנון מקצה לקצה") is the product-level source; this wiki is the engineering one.

## UI map (0.3.3)

`ui/MainActivity.kt` (tabs + navigation) → `CallsScreen` (list) → `CallDetailScreen`;
`SettingsScreen` (arranges `SetupSection`, `ModelSection`, `TranscriptionSwitches`,
`SettingsSection`, `OptionalSection`). Theme in `ui/theme/Theme.kt`; shared pieces
(`Panel`, `Pill`, `Avatar`, `SectionHeader`, formatters) in `ui/Components.kt`.
