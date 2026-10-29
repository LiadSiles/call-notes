# Roadmap and status

_Last updated: 2026-10-22_

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
| 0.6.1 | Items/tasks without cited lines are located by text (`SourceFinder`); visible "הצג בשיחה ›" cue; tasks screen opens the call at that line | ✅ built, awaiting device test |
| 0.7.0 | Tasks: due-date reminder (09:00, inexact alarm, re-planned at boot/update/clock change); "add to calendar" → Google Calendar's prefilled new-event screen; a task repeated in a later call is linked to the first (shown once, not re-announced, ticked together, "not the same task" undo); Whisper speed presets (accurate f16 / balanced q8_0 / fast q5_0 + greedy) with on-device quantization and measured speed per preset; DB v8 | ✅ built (CI green), awaiting device test |
| 0.8.0 | Privacy: user-chosen retention (recordings of transcribed calls / whole calls without open tasks; off by default; confirm before deleting existing), app lock (biometric / PIN after 30 s away; FLAG_SECURE; notification text hidden on the lock screen), search (transcripts, summaries, tasks, names → opens the call at the line), opt-in AAC compression (48 kbps, verified before the WAV is removed); DB v9 | ✅ built (CI green), awaiting device test |
| 0.9.0 | Optional extra encryption (off by default): SQLCipher database (converted at next start; restart button when idle) + recordings sealed with AES-256-CTR after transcription (decrypted on the fly for playback); Keystore-wrapped data key; reversible; lost key → DB set aside with a notice | ✅ built, awaiting device test (restart + accessibility rebind untested) |
| 0.9.1 | Battery: cost of each transcription / summary (fuel-gauge charge counter, whole phone, not while plugged in) in the call's technical details; battery per 10 min of call per speed preset; switch "process only while charging" (recording unaffected; manual requests still run; plugging in resumes the queue); DB v10 | ✅ built, awaiting device test |
| 0.10.0 | Redesign ("quiet ink"): Heebo font bundled (OFL), refined palette light/dark, settings hub (status card + category rows with live summaries) with one page per category, grouped cards per day in the call list, design building blocks in `ui/Design.kt`; screenshot suite (Robolectric + Roborazzi) publishing to the `screenshots` branch | ✅ built, awaiting device test |
| 0.10.1 | Code review: dead code removed (Gemma transcription path, llama.cpp/pyannote entries, unused fields and queries), duplication merged (hashing, WAV header, Viterbi, caller labels, notifications), fixes (task ticks/reminders now survive re-summary and re-transcription; decoder releases MediaCodec on errors; no device-to-device transfer of app data; reminder receiver checks actions), single-pass WAV reading; CI reports branch | ✅ built, awaiting device test |
| 0.11.0 | Length limit for automatic processing (settings → תמלול: none / 5–60 min): longer calls wait for a manual "תמלל" (marked "שיחה ארוכה — לתמלול ידני"); a long call transcribed by hand is then summarised too | ✅ built, awaiting device test |
| 0.12.0 | Every call shows its stage (טרם תומלל / תומלל · טרם סוכם / תומלל וסוכם, or working / failed) in the list and on its screen; automatic transcription and automatic summaries are separate switches on their own settings pages; the length limit is exact (switch + slider 1–120 min + typed 1–600 min); fix: with automatic transcription off, a manual request no longer let the worker go on to transcribe the rest of the queue | ✅ built, awaiting device test |
| 0.22.3 | Sharing a log sends the whole log as a file (and its newest lines as text): apps cut shared text at about 20,000 characters, which dropped the newest lines | ✅ built |
| 0.22.2 | The app no longer gets closed by the phone during a summary: the summary stops before the 6 GB memory line of HyperOS (which also turned accessibility off), a summary that keeps failing is not retried in a loop, and "עצירת הכל" keeps the automatic settings | ✅ built, awaiting device test |
| 0.22.1 | Back to what worked before 0.20.1: the published APK is the debug build again (the optimised build is attached for comparison) and recording runs in the app's process again (the separate process of 0.22.0 could bring old settings back). The processing log now records the automatic switches at every start and every change | ✅ tested: still closed by the phone; its log found the cause (0.22.2) |
| 0.22.0 | Recording in its own process — reverted in 0.22.1 | ↩︎ reverted |
| 0.21.1 | Diagnostics for "accessibility has to be turned on again": every crash and every way Android ended the app is written to the processing log; settings tells "on but not running" (the app's process ended) apart from "off" | ✅ built, awaiting device test |
| 0.21.0 | Recordings from anywhere: the app is a share target for audio — in WhatsApp, share a voice message (or any recording from another app) to it and it is transcribed and turned into tasks at once, in the background; any format Android decodes (Ogg/Opus, M4A, MP3, AMR, WAV…); imported files are always summarised; voice messages are read as messages to the owner | ✅ built, awaiting device test |
| 0.20.1 | Fix: tasks of calls where it isn't known who the owner is stay in the tasks screen ("לא בטוח" until answered). Faster: the published APK is now an optimised release build (was debug), the call list draws one call at a time and only redraws what changed, processing yields the CPU to the screen. Storage page (settings → privacy → אחסון): what takes space, the largest files, and "פינוי מקום" for unused models, unused fast copies, stale and leftover files | ✅ built, awaiting device test |
| 0.20.0 | Better summaries and tasks: the tasks screen has only the owner's tasks — what the other side took on is "ממתין מ…" in the call's summary, without reminders; task titles must be clear without the call (what about, who with); when it isn't known which speaker is the owner, the call asks "which one is you?" and summarises again (its tasks are "לא בטוח" until then); summary length grows with the call and key points hold every concrete detail; optional "thinking" mode (experimental, slower) | ✅ built, awaiting device test |
| 0.19.1 | Task editor: a "לא בטוח" switch on existing tasks — turn the mark off after checking a task (or on) | ✅ built, awaiting device test |
| 0.19.0 | Requests wait in a queue: asking to transcribe or summarise a call (or recording a task) while something runs adds it to the queue — first asked, first served, before automatic work — and nothing in progress is stopped for it (the "stopping the other call first" message is gone). Each request has its own row in the notification shade: waiting with its place ("הבא בתור", "מקום 3"), then its progress, with Cancel. The call screen, the call list and the voice-note cards show the place too | ✅ built, awaiting device test |
| 0.18.3 | Fix: a native crash in Whisper's word timing (DTW) when a window ended within 150 ms killed the app mid-transcription (patched in whisper.cpp; those words keep Whisper's token timing); after any abrupt stop the message and the log now say whether Android reports a native crash or low memory | ✅ built, awaiting device test |
| 0.18.2 | Code review: imports instead of full names (23 files), dead code removed, processing no longer depends on the UI layer, clean-up only on the daily alarm, all-background calls not sent to Gemma, themed launcher icon; wiki: current file maps, new tasks page | ✅ built, awaiting device test |
| 0.18.1 | No lost words: short answers Whisper skipped (even one word, ≥ 0.2 s) and a loud "כן" said over the other side's sentence are read a second time, packed into as few Whisper runs as possible and added only where nothing is there yet; loops trimmed only from the 5th repeat (anything said up to 4 times kept); stock-phrase filter only on long stretches (music), so a real short "תודה" stays | ✅ built, awaiting device test |
| 0.18.0 | Who said what: loudness and voice decided together per word (no longer one or the other); voice fingerprints from short overlapping windows so quick answers aren't merged into the other speaker's turn; "me" decided by: held to the ear → louder, voice sample, louder (speaker off), who answered first — no more "louder = me" on speakerphone; detailed log line per call for tuning | ✅ built, awaiting device test |
| 0.17.1 | Fix: the start of a call could lose ~15 s of speech (hold music, then a faint voice through the earpiece). Stretches with speech energy but no words get a second Whisper pass that never drops a window as "no speech"; loops are trimmed instead of dropping the whole line | ✅ built, awaiting device test |
| 0.17.0 | Background speech (people near either side who aren't on the call) set aside after transcription: hidden in the call screen behind "show", left out of the summary, never deleted; a second person taking part on one side stays (voices of each side, "answered" check); long press to bring a line back or hide one; switch on the speakers page | ✅ built, awaiting device test |
| 0.16.0 | Owner's name and a list of names/words (transcription page) go to Whisper as an initial prompt (with the contact's name; Hebrew only, ≤ 150 chars, kept for every 30 s window) and to the summary prompt; when it's unknown who is who, the call direction hints who spoke first; clearer rules for what is and isn't a task in both summary modes | ✅ built, awaiting device test |
| 0.15.0 | Summaries: whole call always (long calls split into parts with overlap, then merged — nothing cut); "precise" mode (default) asks three questions in one conversation — summary, commitments quoted first, review of them; "quick" keeps one question; calendar + call direction in the prompt; dates checked against the words said; progress "חלק k מתוך n" | ✅ built, awaiting device test |
| 0.14.0 | Tasks without a call: record a task by voice (Whisper → Gemma, ahead of automatic work; note shown in the list until its task exists) or type one; edit any task (title, day, time, owner; delete); reminders pop up at the task's time (or 09:00 on its date) with Done / Snooze (5–60 min, set in settings), on the minute with "Alarms & reminders" access; call summaries also pick up a time of day; timed tasks go to the calendar as timed events | ✅ built, awaiting device test |
| 0.13.0 | "Only while charging" separately for automatic transcription and automatic summaries (each on its page and on the battery page); unplug pauses after a 15 s grace, manual requests continue, paused calls requeue; start on boot when already plugged; fix: a worker stopped while its model loaded no longer let the call run on to the end | ✅ built, awaiting device test |
| later | Model swapping UI: reprocess, comparison screen | |
| next | Path B adapters: Samsung, OnePlus/Oppo/Realme, Vivo | |
| beta | Closed beta (5–10 users), end-to-end test call in setup | |
| 1.0 | Release-signed build, polish, privacy policy, legal review | |

**After 1.0:** VoIP calling mode, names for internet calls, diarization, Bluetooth accessory.

## Waiting on (owner's device tests of 0.6.1)

- Real calls: at the ear, speakerphone, WhatsApp, one long call (10+ min). Check Hebrew
  quality, speed (last log: ~1.1–1.3× call length), speaker labels, summary accuracy,
  parallel transcription + summary (memory/heat), notifications, cancel.
- Still untested on the POCO: reboot survival, Bluetooth headset, recording gaps on
  screen lock (0.2.3/0.3.1 trace), battery-saver pause.

## Next (proposed order, 2026-10-21)

1. Field-test fixes from the logs above.
2. ~~Tasks: reminders, Google Calendar, duplicates~~ (0.7.0). ~~Speed presets~~ (0.7.0).
3. ~~0.8 privacy~~ (0.8.0), ~~optional extra encryption~~ (0.9.0).
4. Before more speed work: owner compares presets on real calls (settings show the
   measured speed of each; re-transcribe a call to compare text).
5. Other phones: Path B (OEM dialer recordings) for devices where the accessibility
   mic capture is silent.
6. 0.9 beta on other phones; 1.0 release signing, privacy policy, legal review.
   Open decision: in-app update checks contact GitHub — conflicts with "no internet
   services"; manual updates may be the rule.
