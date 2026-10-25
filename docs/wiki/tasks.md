# Tasks

_Last verified: 2026-10-25 (code at 0.18.2)._

Where a task comes from, how it is stored, and how it reminds.

## Sources (`tasks.source`)

| Source | How | Code |
|---|---|---|
| `call` | Found by the summary of a call (`CallSummarizer` → `SummaryWorker.analyse`) | `processing/SummaryWorker.kt` |
| `manual` | Typed in the tasks screen ("+ משימה") | `ui/TaskEditor.kt`, `util/TaskActions.save` |
| `voice` | Said into the record button; Whisper → Gemma | `util/VoiceMemos.kt`, `ui/VoiceTask.kt`, both workers, `inference/TaskPrompt.kt` |

Tasks without a call have `callId = 0` (`TaskEntity.NO_CALL`); the task list LEFT JOINs
the calls. A call task the user edits is marked `edited`: a new summary of its call keeps
it and drops its own version of it (same title or quote). Deleting a call task: a later
re-summary may bring it back (the confirm dialog says so).

## Voice notes (`voice_memos`)

1. Recorded in the app (16 kHz WAV in `files/memos/`, ≤ 2 min, stops when the app leaves the
   screen; too short / silent → not kept).
2. `RECORDED` → the transcribe worker takes it before any call (pausing an automatic call),
   ignoring the automatic switches and "only while charging"; battery saver still applies.
   The audio is deleted once the text is saved → `TRANSCRIBED`.
3. The summary worker asks Gemma (`TaskPrompt`) → task(s) with day and time; the note row is
   replaced by them in one transaction. No task found → `NO_TASK` (text kept, "write a
   task" opens the editor with it). Errors → `FAILED` with retry.
4. Interrupted notes resume when the app opens or battery saver ends
   (`VoiceMemos.resume`); a note is shown at the top of the task list until it is done.

## Reminders

- When: `dueDate` at `dueTime`, or 09:00 (`TaskReminders.HOUR`) when only a day is set.
  `reminderAt` (pure, tested) also covers snooze.
- One alarm for the next reminder; exact (`setExactAndAllowWhileIdle`) when
  `SCHEDULE_EXACT_ALARM` is granted ("Alarms & reminders", user-revocable; settings show
  the state and open the system page), else inexact. Re-planned when tasks change, at boot,
  update, clock/time-zone change and when that permission changes.
- Notification channel "תזכורות למשימות" (high importance → pops up), with Done and Snooze
  (5–60 min, settings). Buttons go to a non-exported receiver. Done clears the snooze;
  changing a task's day or time re-arms its reminder.
- New tasks from a call notify on the quieter "משימות חדשות" channel; a voice task notifies
  only if the app is not on screen.
