# Decisions

Newest first. Each entry: what was decided, why, and what was rejected. Add an entry
whenever a choice would surprise a future contributor.

### 2026-10-28 — Release build, lighter list, storage page; tasks of unknown speakers stay visible (0.20.1)
Owner: tasks don't reach the tasks screen after a summary; the app is slow (scrolling);
it takes almost 90 GB.
- Tasks: since 0.20.0 the other side's commitments are hidden from the tasks screen. When
  it isn't known which speaker is the owner, "owner" is the model's guess — a task of the
  owner guessed as "other" disappeared. Such calls now store every task as owner
  "unknown" (and "לא בטוח") until the user answers "which one is you?" and the call is
  summarised again. The log line counts the model's owners separately.
- Speed: the published APK was the debug build (debuggable: no ahead-of-time
  compilation, Compose debug checks, no R8). CI now builds `assembleRelease` — R8
  shrinking and optimisation with `-dontobfuscate` (stack traces in the log stay readable;
  JNI-reached classes of sherpa-onnx, LiteRT-LM, SQLCipher and our Whisper bridge are kept),
  resource shrinking, signed with the same committed key so it installs over earlier
  builds. Unit tests still run on debug. The call list drew a whole day as one lazy item
  and every progress tick recomposed it: now one item per call (`CardSlice` keeps the
  card look), grouping remembered, and each row gets only its own stage label so other
  rows skip. Processing threads run at nice +5 (`InferenceThreads`; native engines'
  threads inherit it) so the screen wins the CPU while a call is processed; not
  THREAD_PRIORITY_BACKGROUND, which may pin threads to the slow cores.
- Storage: the models are about 6–9 GB (Whisper 1.6 + its fast copies 0.6–0.9 each,
  Gemma 3.7 or 2.6, LiteRT's working copy in the cache); nothing in the code explains
  90 GB, so the app now measures itself: settings → privacy → אחסון lists sizes by kind
  and the largest files, and frees only what is unused or re-creatable (models not in use,
  fast copies of other presets, stale cache files, leftovers of stopped downloads), after
  a confirm, not while processing. Start-up removes temp audio and half-made model copies
  a stopped run left. To find the real cause, the owner's numbers from that page are
  needed.

### 2026-10-25 — Only the owner's tasks; titles that stand alone; fuller summaries; thinking option (0.20.0)
Owner: summaries too short and inaccurate, many tasks pointless — e.g. "דובר 1 told דובר 2
he'll get back to him with an answer": about what? and not the owner's task at all. Asked
whether Gemma 4 E4B is the strongest model: it is the largest Gemma 4 that LiteRT-LM runs
on phones (26B A4B / 31B are not supported on phones and don't fit 12 GB); a Hebrew model
(DictaLM 3.0 12B via llama.cpp) lost to Gemma 3 12B in Dicta's own summary evaluation and
would take minutes per part on the phone — rejected for now. Fine-tuning discussed:
possible only on the owner's own GPU computer (no cloud with personal calls), the
Gemma 4 E4B → .litertlm conversion is not documented yet; deferred until corrections
collected in the app can serve as data. Causes found and fixed:
- The example's labels show the owner's speaker was unknown, so whose task it was could
  only be guessed. Tasks of such a call are now "לא בטוח", and the call screen asks
  "which speaker is you?" with a line of each; answering sets `meSpeaker` and summarises
  again.
- Every commitment became a task, the other side's too. Tasks with `owner = other` stay in
  the table but are "ממתין מ…" in their call's summary only: not in the tasks screen or its
  count, no reminder, no new-task notification, not used as an original for repeats, and
  they don't keep a call from retention. Changing the owner in the editor moves a task.
- Titles: "a verb, up to 10 words" allowed "להחזיר תשובה". Now: understandable a week
  later without the call — what it is about and who it involves (from the lines before a
  promise like "אחזור אליך"); `certain: false` if the call never says; no speaker labels.
  The worked example shows it; the review question rewrites titles that fail it and
  checks the owner.
- Length: "2-4 sentences" for every call and "accuracy over completeness" made summaries
  thin. Now 1–2 … 6–8 sentences by transcript length (5–10 for merged long calls), topic by
  topic, and key points list every concrete detail (names, amounts, dates, places,
  numbers). Answers up to 2048 tokens (was 1536).
- Thinking (Gemma 4 reasons before answering), off by default, in settings → summaries:
  engine context 16384 tokens (falls back to 8192 if the model file refuses), answers up
  to 4096 tokens, parts of ~4.5k characters instead of ~6k. Expected 2–4× slower. Not
  verified on the device yet: the log line "thinking on, context N tokens" says what ran.

### 2026-10-25 — Requests queue up; nothing in progress is interrupted (0.19.0)
Owner: several requests at once — each new one joins a queue and waits its turn, the next
starts when one finishes, each request has its own row in the notification shade, and the
call screen must stop saying it is stopping the current process to start this one.
Before: a manual transcription paused an automatic call in progress (back to the queue,
restarted from the start later) and a voice note went ahead of everything, also pausing an
automatic call; between two manual requests the order was kept, but the screen still said
"מתחיל… עוצר קודם תמלול אוטומטי של שיחה אחרת".
- `ProcessingStatus.queue`: `Request(stage, callId | memoId, at)` sorted by `at` — first
  asked, first served. Calls and voice notes share the order; a note keeps its recording
  time for both stages (after a restart the in-memory queue is empty, so notes are read back
  from `voice_memos` with that time — `syncMemos`). Asking again keeps the first place.
- Each worker takes the first request of its stage only when it is free; requests go before
  automatic work. The one exception that is not an interruption: an automatic call whose
  model was still loading when a request came is put back (nothing had started).
  Transcription and summaries are still two queues running at the same time (0.5.5); each
  runs one thing at a time.
- A worker that ends restarts only if a request was added during its run
  (`ProcessingStatus.enqueued` vs `Worker.requestsSeen`) — a request it couldn't take
  (battery saver, model missing) must not restart it in a loop.
- Notification rows: one per request, id `Notifications.rowId(stage, call | note)`
  (negative, clear of task ids), so the waiting row ("ממתין לתמלול: דני · 21.10 14:05 —
  מקום 2 בתור") becomes the progress row when its turn comes. Waiting rows carry Cancel
  (for a voice note = delete it, as in the app); shade order by `when` (earlier place =
  later time). The service is foreground with one row and moves to another before that one
  is removed; a "מכין…" row only when there is no other. Rejected: a notification group
  with a summary — collapses the rows the owner asked to see.
- UI: "בתור לתמלול · מקום N" / "בתור לסיכום · מקום N" with Cancel on the call screen; the
  list pill and voice-note cards show the place; "תמלל עכשיו" reads "הוספה לתור" while
  something else is being transcribed.

### 2026-10-25 — Patch whisper.cpp: no DTW on windows under 160 ms (0.18.3)
Owner sent a native crash from 0.17.1 (`abort` inside `WhisperNative.transcribe`). The
library is stripped, so it was traced by disassembly: the abort is
`WHISPER_ASSERT(filter_width < a->ne[2])` in whisper.cpp's DTW `median_filter`
(src/whisper.cpp:9136, reached through ggml's custom-op dispatch). `whisper_full` runs DTW
on every window that produced a segment with `n_frames = min(seek_delta, seek_end − seek)`;
the median filter has width 7 and asserts more than 7 audio tokens (20 ms each), so a
window whose last timestamp — or the audio left — is ≤ 150 ms kills the whole process.
Rare per window, but every call and every second-look run is many windows; it is still in
upstream v1.9.5, and 0.18.2 ships the same library (same build ID). The process died, so
the call was marked "stopped midway — maybe out of memory" and a retry hit it again.
- Fix: `app/src/main/cpp/patches/whisper-dtw-short-window.patch` skips DTW for such a
  window; its tokens keep `t_dtw = -1`, and `WhisperOutput` already falls back to token
  timing. CMake applies the patch to the submodule before building (skipped if already
  applied; a patch that no longer fits after a submodule bump stops the build).
  Rejected: padding the audio with silence (covers only the end of the audio, not a short
  last timestamp); a SIGABRT handler (no safe way back into the JVM).
- The restart message now uses Android's exit reason (`ApplicationExitInfo`, Android 11+):
  a native crash says so instead of blaming memory, and the log line records the reason.

### 2026-10-25 — Never lose a word: second look at short and overlapped speech (0.18.1)
Owner: short answers of his still disappear; losing anything said is not acceptable.
Audit of every place text could go:
- Whisper itself skips short backchannel answers ("כן", "בסדר" — it is trained on cleaned
  subtitles) and, on a call held to the ear, keeps one voice where the owner's "כן" lands on
  the other side's sentence. The 0.17.1 second look needed ≥ 1 s of uncovered sound, so a
  0.4 s answer was never re-read. `MissedSpeech` (replaces `SpeechGaps`): uncovered sound
  ≥ 0.2 s, plus frames at the owner's level (2-means on word medians, ≥ 10 dB apart) inside
  a word whose median is the quiet level. Stretches ±0.3 s are packed with 0.8 s silence
  into ≤ 28 s buffers (whisper.cpp costs a full 30 s window per run, so one run per pack,
  not per answer); words map back to the recording, kept only inside a looked-at stretch
  and only if no word with the same text is within 0.4 s.
- Loop trimming (0.17.1) dropped the 3rd+ repeat of any 1–4-token block — including
  "כן / כן / כן" across turns. Now only runs of ≥ 5 repeats are loops, 3 kept.
- The stock-phrase filter ("תודה רבה"…) applied to every second-look line; now only to
  lines from stretches ≥ 3 s (music on hold) — a short "תודה" is a real answer.
- Background speech is hidden, not removed (toggle in the call screen) — unchanged.

### 2026-10-25 — Speakers: loudness and voice together, per word (0.18.0)
Owner: who-said-what is still often confused. Two structural causes found in the code
(no per-call log was available): (1) voice windows of up to 3 s cut only at pauses
≥ 400 ms merged a quick answer into the other speaker's window; (2) loudness *or* voice
was used — a 5–7 dB gap was thrown away, a 7 dB gap ignored the voices — and on
speakerphone without a voice sample "louder = me" was assumed. `SpeakerDecoder` weighs
both per word (each by how clearly it separates this call), voices from 1.5 s windows
every 0.75 s; "me" by a ranked rule list incl. who answered first. Cost: about 2–3× the
fingerprints of before on speakerphone/VoIP, and fingerprints on handset calls too (with
the voice model) — the voice evidence now also helps there. Thresholds are first
guesses; the per-call log line has what is needed to tune them on real calls.

### 2026-10-25 — Second look at speech Whisper skipped; trim loops, keep lines (0.17.1)
Owner report: incoming call with hold music, then a receptionist; her first ~15 s were
missing (trace: audio present the whole time, faint −48 dBFS through the earpiece).
- Cause 1: whisper.cpp judges each 30 s window: `no_speech_prob > 0.6` (music at the
  start) and `avg_logprob < −1` (faint speech) → the whole window's text is discarded and
  decoding jumps to the last timestamp. Turning the gate off globally was rejected: it also
  turns on temperature-fallback retries in every doubtful window (slower, more invention).
  Instead `SpeechGaps` (now `MissedSpeech`, 0.18.1) finds 100 ms frames ≥ 12 dB over the recording's floor (10th
  percentile) with no word near them (≥ 1 s of sound, joined within 1.5 s, ±0.4 s, pieces
  ≤ 28 s) and re-reads each alone with `no_speech_thold = 1`, `temperature_inc = 0`
  (JNI `keepQuiet`). Lines that are only Whisper's stock filler ("תודה רבה", "כתוביות"…)
  from those pieces are not kept. Logged per call.
- Cause 2: a line containing a decoding loop was dropped whole — with the real words
  next to the loop. Now only the repeats go (a 1–4-token block repeating the two before
  it; a phrase said twice stays), in lines and in the word list alike (the word list was
  not filtered before, so loops came back through the speaker step).

### 2026-10-24 — Background speech set aside, not deleted (0.17.0)
Owner: other people talking in the background (on either side) hurt transcripts most;
filter it on both sides, but a side may really have two people on the call — keep them;
only speech that isn't the side's speaker should go.
- Denoising and VAD don't help here: both keep speech, and background talk is speech.
- `BackgroundSpeech` works on the voice windows (≤ 3 s) after speaker separation. Voices
  of a side = voices heard at the side's level with ≥ 15 % of it (one or two people) +
  the owner's sample. A window is foreign if unlike all voices of both sides (cos < 0.40 —
  a mis-sided owner word is never foreign). Background = foreign AND quiet for its side
  (≥ 10 dB under the side's p75 level) AND not answered (the other side doesn't start
  within 1.5 s after it, in ≥ 1/3 of its windows and ≥ 2 times — answered quiet voices
  are people joining in). Foreign at full level only if ≤ 10 % of its side and never
  answered. Too-short windows follow a neighbour within 1 s.
- Safety: needs both sides known and voice fingerprints (voice model); skips entirely if
  it would set aside > 35 % of the speech. All thresholds lean towards keeping speech.
- Level-separated calls (handset) now fingerprint all ≥ 1 s windows too (only when the
  filter is on and the voice model installed) — the price of the check.
- Never deleted: `segments.background` hides the line (call screen toggle), the summary
  skips it while line numbers stay the segment numbers. Long press brings a line back or
  hides one; re-transcribing re-evaluates. Rejected for now: target-speaker extraction
  models (overlapping speech) — heavy, unproven on Hebrew phone audio.

### 2026-10-24 — Expected words for Whisper; owner's name and task rules for Gemma (0.16.0)
Owner: cheap, safe improvements — call direction, the owner's name, what is and isn't a
task, and better input to Whisper.
- Whisper `initial_prompt` with `carry_initial_prompt` (whisper.cpp 1.9.5; re-sent before
  every 30 s window): owner's name, contact name, the user's word list. Plain
  comma-separated list, Hebrew-script words only (a Latin contact name could pull the
  output to English), ≤ 150 chars (shares the 224-token text budget; a long prompt is more
  likely to be "heard" in silence). Built by `WhisperPrompt`. Voice notes get the owner's
  name and the list.
- Gemma gets the name ("whoever is called this is the owner") and the list (correct
  spellings of words ASR may mangle). With speakers unknown, the direction says who
  probably spoke first (whoever picks up says "הלו").
- Task rules (`AnalysisPrompt.ARE_TASKS` / `NOT_TASKS`) shared by quick and precise:
  short accepted requests count; a call/meeting set for a time counts; conditional,
  wishes, already done (even during the call), declined, unanswered questions and polite
  closings without a time don't; tasks of absent people only if a speaker took them on.
- Name and word list are personal data, kept on the device in prefs (not in backups) and
  not cleared by "reset settings".

### 2026-10-23 — Precise summaries in three questions; whole call, never cut (0.15.0)
Owner: split the summary into focused steps for better tasks, and take the whole call
into account — no information lost.
- Three questions in one LiteRT-LM conversation (`Analyzer.conversation`), not three
  separate prompts: the transcript is prefilled once, follow-ups cost only their answers.
  Expected cost ~+30–50 % time vs one question (to be measured on the device; the log has
  per-step times). Order: summary first (the model "understands" the call before hunting
  tasks), then commitments with the quote as the first field (grounding before wording),
  then a self-review that may only remove, merge or fix. "Quick" (one question) stays as
  a setting.
- Long calls: parts of ~6k chars with a 2-line overlap instead of cutting the middle
  (≤ 9k chars stays one piece — fits 8192 tokens with three questions and answers). Notes
  are merged by the model in rounds; the merge refers to items by id so their source lines
  survive, and anything it leaves out is re-added — the merge can shorten, never drop.
  Tasks are merged in code, strictly (same title; all words of one in the other; or mostly
  the same words plus a shared line) — "…לדני" vs "…לספק" stay two. Rejected: a bigger
  context window instead of parts — more memory next to Whisper, and small models lose
  the middle of long inputs anyway.
- Dates: a 15-day calendar with Hebrew day names in the prompt (look up, don't compute)
  and `DueWords` checks the result against the words said.

### 2026-10-23 — Tasks by voice and by hand; reminders at the set time (0.14.0)
Owner: add tasks with a record button (Gemma 4 writes the task), add by typing, edit
existing tasks, and make reminders pop up at the task's time with a snooze.
- Voice: Whisper first, then Gemma reads the text (`TaskPrompt`). Rejected for now: giving
  Gemma the audio directly — the Hebrew-tuned Whisper is far more accurate on Hebrew, and
  the LiteRT-LM path in use is text only. The note runs through the existing two workers
  (one model of each kind in memory, the same memory/thermal/battery-saver rules) and
  goes ahead of everything: an automatic call in progress is paused and requeued, like a
  manual request. Not held back by the automatic switches or "only while charging" (the
  user asked for it). The audio lives in `files/memos/` only until transcribed (seconds),
  so it is not sealed even with extra encryption on — same rule as a call waiting for
  transcription. A note stays in `voice_memos` (shown at the top of the task list) until
  its task exists, so it survives the app being closed; "no task found" keeps the text so
  the user can write the task from it. Recording stops at two minutes or when the app
  leaves the screen (no background mic).
- Tasks without a call use `callId = 0` (no call has id 0) rather than a nullable column:
  SQLite can't relax NOT NULL without rebuilding the table; the task list LEFT JOINs.
- Edited call tasks are flagged; a new summary of that call keeps them and drops its own
  version of them (same title or same quote) instead of overwriting the user's edit.
  Deleting a call task: a later re-summary may bring it back (said in the confirm dialog).
- Reminders: time of day `dueTime` (else 09:00 on the date). Heads-up needs a
  high-importance channel, so reminders got their own channel ("תזכורות למשימות"); new
  tasks stay on the quieter one. Done / Snooze go to a non-exported receiver. One alarm
  for the next reminder; exact (`setExactAndAllowWhileIdle`) when `SCHEDULE_EXACT_ALARM` is
  granted — user-grantable and revocable in "Alarms & reminders", unlike `USE_EXACT_ALARM`
  (install-time, can't be revoked; rejected for the minimal/revocable-permissions rule).
  Without it reminders stay inexact (may be minutes late) and settings says so.
  Snooze sets `snoozedUntil`; ticking done clears it; changing date/time re-arms.
- Call summaries now also return `due_time` when a clock time was said.

### 2026-10-23 — "Only while charging" per step, and what unplugging does (0.13.0)
Owner: separate "transcribe automatically while charging" and "summarise automatically
while charging", with every edge case covered. `Prefs.transcribeOnlyCharging` /
`summarizeOnlyCharging` default to the old shared switch. Rules:
- Gate: `Energy.autoTranscribeAllowed` / `autoSummaryAllowed` (step on, and plugged in if
  it must be) decide what the workers pick automatically and whether an automatic start
  of the service happens at all. Manual requests ignore both.
- Unplugged: after a 15 s grace (`CallNotesApp.UNPLUG_GRACE_MS` — loose cable, wireless pad,
  quick replug), `TranscriptionService.pauseForCharger` stops each worker whose step is
  charging-only, unless its current call was requested by the user. Stopped transcription
  → the call returns to the queue (an earlier transcript stays) and restarts from the
  beginning later; stopped summary → redone later, old summary/tasks kept; a model copy
  in preparation → discarded. Requested calls still queued run after the stop. Chosen
  over "finish the current call": a long call could otherwise run 30+ min on battery.
- Plugged in: the queue starts at once; also checked at app start (booted on the charger).
- Turning the switch on while unplugged pauses that step now; turning it off resumes.
- Found while testing the paths: if a worker was stopped while its model was loading,
  `runItem` still started the call outside the worker's job and waited for it to finish
  (stop-all had the same hole). It now refuses to start and cancels a running call.

### 2026-10-23 — Separate automatic switches; one stage label per call (0.12.0)
Owner: each call must say clearly whether it was summarised, transcribed or nothing yet;
automatic transcription and automatic summaries are separate settings on their own pages;
the length limit is set exactly (slider or typed). `Prefs.autoSummarize` follows
`autoTranscribe` until first set, so existing users see no change. "Stop all" turns both
off. The length limit stays one value (transcription page) and applies to both — a
summary needs a transcript, and a long call transcribed by hand is still summarised
right after if automatic summaries are on. `callStage()` is the single source of the
label shown in the list and on the call screen. Found while doing this: the transcribe
worker took the next pending call even with automatic transcription off once the service
was running (e.g. for one manual request); it now only takes requested calls then.

### 2026-10-22 — Length limit for automatic processing (0.11.0)
Owner: let the user choose a call length above which nothing is transcribed or
summarised automatically; shorter calls keep going automatically. `Prefs.autoMaxMinutes`
(0 = no limit) filters the automatic queues in SQL (`nextPending`, `nextUnanalyzed`,
their counts) and the after-call start; manual requests ignore it. A long call the user
transcribes by hand is queued for its summary right after (otherwise it would sit
"transcribed, not summarised" because the automatic summary skips it too).

Also recorded here (owner's question): battery saver turning on mid-run, with "pause in
battery saver" on, stops both workers at once; the call being transcribed goes back to
the queue (or keeps its previous transcript) and restarts from the beginning later, a
summary in progress is redone later, a model-copy preparation is discarded; a call being
recorded is recorded to its end. With the setting off nothing changes (Android may slow
the CPU). Turning saver off resumes automatically.

### 2026-10-22 — Code review: what changed and what was kept on purpose (0.10.1)
Owner asked for a senior-level review: readable, documented, no unused code, faster
where possible, no change to anything in use.
- Removed: Gemma-as-transcriber (GemmaTranscriber, Chunker, AudioLevel, chunk-overlap
  merging) — unreachable since 0.4 (no catalog entry); `InferenceEngine.LLAMA_CPP`;
  the pyannote ModelSpec/role (its file is still deleted on start via `RETIRED_FILES`);
  unused fields (Whisper token probability end-to-end incl. JNI, task confidence,
  analysis language), unused DAO queries, a "stopping" phase nothing ever set.
- Fixed: `clearAnalysis` deleted a call's tasks before the summary worker could carry
  ticks/reminders/repeat links over by title, so "סכם מחדש" and re-transcription lost
  them and re-announced every task. Tasks now stay until `replaceAnalysis` swaps them.
  Also: MediaCodec leak on decode errors, Android 12+ device-to-device transfer of app
  data (`dataExtractionRules` now excludes everything), exported reminder receiver
  accepted any action, non-atomic ServiceStatus updates, model polling on the main thread.
- Kept on purpose: the vendored sherpa-onnx Kotlin files (verbatim copy, see README);
  the capture path's logic (device-tested; only imports and helpers touched); the
  manual LazyColumn index arithmetic in the call screen (documented; a key-based jump
  needs a larger refactor); `AnalysisPrompt.contains` leniency (tightening it would
  mark more items "not sure" — a behaviour change to decide on separately).

### 2026-10-22 — Redesign: calm hub + pages, bundled font, screenshots in CI (0.10.0)
Owner: pages should be clearer, calmer and cleaner; settings was crowded; wants an
elegant, modern look. Settings became a hub — a status card and one row per category
(icon tile, title, one-line summary of what's set) — with each category on its own page,
so the long explanations moved off the main screen into short notes. Look: warm mist
background, white cards without borders or shadows, inset hairlines, deep pine used
sparingly, one soft tint per category. Font: Heebo (SIL OFL, `res/font/heebo.ttf`,
variable weight) bundled — a downloadable Google Font would contact Google's servers,
which the no-internet-services rule forbids. Extra icons are drawn from SVG paths
(`AppIcons`) rather than adding material-icons-extended. To review layouts without a
phone, `ScreenshotTest` renders stateless screen pieces with sample data on
Robolectric (native graphics) and `.github/workflows/screenshots.yml` pushes the PNGs to
the `screenshots` branch (`<branch>/*.png`); excluded from the normal build
(`-Pscreenshots`). Side effect: `ui-test-manifest` (a test-only activity declaration) is
in the debug APK.

### 2026-10-22 — Battery: measure, and let the owner defer work to the charger (0.9.1)
Owner asked how much energy the app uses and wanted a switch to process only while
charging. Nearly all energy is Whisper and Gemma after a call (all fast cores); the
accessibility service and the recording are light. Each operation reads
`BATTERY_PROPERTY_CHARGE_COUNTER` (µAh; whole percent where unsupported) before and
after → percent of an estimated full charge, stored per call (`calls.transcribeEnergy`,
`analyzeEnergy`, `transcribeMs`, `analyzeMs` — DB v10) and summed per speed preset. It is
the whole phone's drain in that window (screen, other apps, a parallel summary), so an
upper bound; nothing is recorded while plugged in. "Only while charging" (= plugged in,
`EXTRA_PLUGGED`, full or not) gates the automatic queue in both workers and in
`TranscriptionService.start`; explicit taps still run; unplugging lets the current call
finish; `ACTION_POWER_CONNECTED` (registered at runtime — not deliverable to a manifest
receiver since Android 8) restarts the queue.

### 2026-10-22 — Extra encryption is opt-in and reversible (0.9.0)
Android's file-based encryption already protects app storage at rest with the lock
screen; app-level encryption adds protection for a copied data folder, a rooted phone or
a forensic image of an unlocked one — at the cost of losing everything if the Keystore key
is ever wiped. So it's a switch, off by default, with that risk stated in the confirmation.
Design: one random 256-bit data key, stored only wrapped (AES-GCM) by a non-exportable
Android Keystore key (`security/KeyVault`). Database: SQLCipher with the raw key
(`x'…'`, no PBKDF2 delay), converted with `sqlcipher_export` before Room opens it, so a
change takes effect at the next app start (settings offer a restart through a tiny
`:restart`-process activity, enabled only when nothing records or processes). Recordings:
"CNE1" + IV + AES-256-CTR, sealed after transcription (and compression), so a call in
progress or waiting for transcription is still a plain file in private storage; CTR lets
`MediaDataSource` decrypt any position for the player; transcription and sharing use a
temporary plain copy in `cache/plain|share`, cleared at app start. A database that won't
open with the key is renamed `*.unreadable-<time>`, sealed recordings deleted, the user
told — rather than a crash loop.

### 2026-10-22 — Retention is the owner's choice, off by default (0.8.0)
Owner: "auto delete will be determined by the user". Two independent settings: recordings
of transcribed calls after N days (text stays), whole calls after N days. Nothing is
deleted until chosen; picking a period that already covers existing items shows the
count and asks first. Calls with an open task are kept (deleting them would silently drop
a to-do). Untranscribed recordings are never deleted by the recordings rule. Runs daily
(inexact alarm), at app start and on change.

### 2026-10-22 — App lock is optional and covers what others can see (0.8.0)
Off by default. On: BiometricPrompt (weak biometric or the phone's PIN/pattern) when the
app opens or returns after 30 s, `FLAG_SECURE` (no screenshots, blank recents preview),
and task / processing notifications become `VISIBILITY_PRIVATE` with a generic public
version. Recording and processing are unaffected. Turning it on or off asks for the lock
first. New install-time permissions, no prompts: `USE_BIOMETRIC` (from androidx.biometric),
and `RECEIVE_BOOT_COMPLETED` (0.7.0, re-plans task reminders).

### 2026-10-22 — Search is a plain substring match (0.8.0)
Hebrew attaches prefixes (ו ה ב ל מ ש כ) to words, so FTS tokens miss "מחיר" in "המחיר";
a `LIKE '%q%'` over a few thousand transcript lines is instant and finds both. FTS can
come later if libraries grow large.

### 2026-10-22 — Compression is opt-in (0.8.0)
The owner once reported recordings sounding worse after transcription (a bug, fixed), so
lossy re-encoding is never applied silently: AAC 48 kbps mono, only after the transcript
is saved, the WAV removed only after the .m4a's duration matches.

### 2026-10-22 — Calendar = Google Calendar, through its own editor (0.7.0)
Owner chose Google Calendar. The app opens Calendar's prefilled "new event" screen
(`ACTION_INSERT`, package `com.google.android.calendar`, any calendar app as fallback)
instead of writing events itself: no calendar permission, and nothing leaves the app
until the user taps Save there. Only the task title, the due phrase and the call label go
in — not the quote. Google Calendar syncs to the user's Google account; that's the user's
explicit, per-task choice.

### 2026-10-22 — Repeated tasks: strict match, original keeps the identity (0.7.0)
A task in a new call that matches an open task from an earlier call (last 60 days) is
stored with `duplicateOf` = that task: shown once (under the original, with "came up
again" links), not announced again, no second reminder, ticking either ticks both.
Matching (`TaskMatcher`) is deliberately strict — ≥ 75 % of the shorter title's content
words (Hebrew prefixes folded), same owner and no conflicting due dates — because a
wrong merge hides a task while a missed one only shows it twice. Undo: "לא אותה משימה".

### 2026-10-22 — Faster Whisper by quantizing on the phone (0.7.0)
No quantized ivrit.ai build is published, and the app must not use a server. The app
writes q8_0 / q5_0 copies of the downloaded f16 model itself, with a streamed port of
whisper.cpp's `quantize` (same tensor rules, row chunks → low memory, progress, cancel).
The copy is made once, the first time a faster preset transcribes; the f16 file is kept
so "accurate" stays one tap away. Settings show measured speed per preset on this phone
(× call length) rather than promised numbers.

### 2026-10-21 — Summaries must show their evidence (0.6.0)
Owner: summaries, key points and tasks must be very accurate, and anything uncertain must
be marked. Rather than trust the model's self-assessment alone, every item cites
transcript line numbers and a `certain` flag, and `AnalysisPrompt.parse` downgrades to
uncertain anything it can't verify: cited lines out of range or missing, a task quote not
found in its lines (normalised, ≥ 75 % of words), a due date it had to drop. The cited
line doubles as the link from the item to the transcript. Pre-0.6 summaries (plain
strings) still load, as uncited points.

### 2026-10-21 — Speaker labels: physics first, user has the last word (0.6.0)
At a ≥ 15 dB level gap the phone was at the owner's ear; louder = owner, and the voice
sample (compared against a distorted earpiece leak) may not overrule it. Users fix the
rest: long-press a line (this line is me / them), or swap the whole call
(`calls.meSpeaker`). The summary then offers to re-run.

### 2026-10-21 — Two parallel workers, instant cancel (0.5.5)
Owner wants transcription and summaries at the same time, a temporary notification per
operation naming the call, and cancel that acts at once. `TranscriptionService` now hosts
`TranscribeWorker` and `SummaryWorker` (`processing/Worker.kt` base): own model, queue,
`ProcessingStatus.state` / `.summary`, notification (`ID_TRANSCRIBE` / `ID_SUMMARY`).
The service is in the foreground with whichever notification is live (re-anchored when one
finishes) and stops when both are idle. Memory: Gemma's weights are mmapped; before
loading it next to Whisper the summary worker waits while the phone reports low memory
(`availMem` < 1.5 GB). Cancel updates UI state + DB + notification synchronously and then
aborts the engine (whisper.cpp abort callback; LiteRT-LM `sendMessageAsync` +
`Conversation.cancelProcess()`); results arriving for a cancelled call are dropped.
"Stop all" returns the running calls to the queue and turns automatic processing off.

### 2026-10-21 — No third-party native pipeline that can exit the process (0.5.3)
sherpa-onnx's diarization pipeline aborts with `exit(-1)` on inputs it dislikes; in an
app that's indistinguishable from a crash and can't be caught. We keep only its
smallest, well-defined piece (fingerprint extraction), validate its inputs in Kotlin,
and do segmentation/clustering ourselves on top of Whisper's word timing (pure Kotlin,
unit-tested). Rule for future native dependencies: grep their sources for
`exit(`/`abort(` on reachable paths before adopting them.

### 2026-10-21 — Loudness first, voice embeddings second, for who-said-what (0.5.2)
With one microphone at the owner's mouth, the strongest cue in a handset call is the
level gap between the owner and the earpiece leak; general-purpose diarization
(pyannote) is built for comparable voices and failed on the POCO (one speaker). So the
level method runs first and the voice method only when levels are close. Rejected:
lowering the merge threshold (would still split the owner's voice in two), requiring
speakerphone (the owner holds the phone to the ear).

### 2026-10-21 — Hebrew Whisper + local diarization; Gemma for understanding (0.4)
Owner: transcripts must say who said what, everything strictly on-device ("the whole
point of the app is to guarantee privacy" — no cloud, no internet services; network
only to download models). Chosen pipeline:
1. **Transcription:** ivrit.ai Hebrew fine-tune of Whisper large-v3-turbo, ggml for
   whisper.cpp. Their Interspeech 2025 paper (large-v2 fine-tune) reports 6.2 % WER on
   two-person conversation vs 8–9.6 % for OpenAI Whisper and 21 % for Google Speech.
2. **Speakers:** pyannote segmentation-3.0 + a speaker-embedding model, clustered into
   exactly 2 (a phone call). Voice-based, language-independent.
3. **Who is "me":** a one-time voice sample of the owner; nearest cluster = me.
4. **Understanding:** Gemma 4 reads the labelled transcript → summary, tasks.
Rejected: Gemma audio for ASR (poor Hebrew, no published Hebrew numbers); cloud ASR
(AWS/ElevenLabs/Soniox — excellent Hebrew but violates the privacy rule); wav2vec2 /
small Hebrew models (faster, less accurate — maybe a "light mode" later).
Supersedes "Gemma 4 for both transcription and analysis" (2026-10-18).

### 2026-10-21 — Gemma ASR on its documented template (0.3.8)
Owner found 0.3.x Hebrew transcripts mostly unusable. First fix stays within Gemma:
the trained ASR prompt, instruction before audio, no system instruction, normalized
input level. Next option if that's not enough: a dedicated Hebrew ASR model —
ivrit.ai's Hebrew fine-tune of Whisper large-v3-turbo has a ggml build for whisper.cpp
(Apache-2.0). That fits the existing `Transcriber` interface; Gemma would stay the
Analyzer. Owner decides (it changes the "Gemma only" choice of 2026-10-18).

### 2026-10-21 — Every started action can be undone (0.3.7)
Owner rule: anything the user starts needs a visible way back.
- Cancel one call → status `held`: skipped by automatic runs (`nextPending` only takes
  `recorded`), manual "transcribe now" still works. A cancelled re-transcription goes
  back to `transcribed`: old segments are only replaced when the new transcript is
  complete (`CallDao.revertTranscription`). Status changes during transcription use
  `setStatusOnly` so the old transcript's model name survives.
- Stop all (list panel, notification action `ACTION_STOP`) → cancels the run and turns
  `autoTranscribe` off; otherwise the next app open would restart it at once. The list
  shows "automatic transcription is off · N waiting · turn back on".
- The engine is closed only after a cancelled chunk's native call returns (`join`), never
  under it.
- Setup steps and optional permissions show their reverse once done. Android lets an app
  request a permission or exemption but not give it back, so "undo" opens the system
  screen (app details, accessibility, battery-optimisation list). "Restore defaults"
  resets the switches only — never model, permissions or data.

### 2026-10-21 — Transcription queue order, feedback and log (0.3.5)
Bug seen on the POCO: with a model installed, opening the app starts an automatic run
over every pending recording (newest first). "Transcribe now" on another call only
woke the running service, so the tapped call stayed "waiting" with no visible change.
Now `TranscriptionService.start(callId=…)` puts the call in `ProcessingStatus.requested`
(the UI shows "starting" at once); the service takes requests first and cancels an
automatic run on a different call between chunks (that call returns to `recorded` —
the reset runs under `NonCancellable`, before it was silently skipped). The model load
is an explicit phase (`Transcriber.load()`) shown with elapsed time.
`ProcessingLog` writes `files/transcription.log` (timings, free RAM, chunk counts,
exceptions with top frames; never transcript text) and persists the current stage
synchronously in prefs. `CallNotesApp.onCreate` treats a leftover stage as a run the
system killed (OOM / native crash): logs it, notes it, and fails the stuck call with
that stage so it gets a retry button. Rejected for now: a separate `:transcribe`
process (would survive a native crash but needs cross-process status + Room
multi-instance invalidation) — revisit if the log shows engine crashes.

### 2026-10-21 — Navigation and list state (0.3.4)
The call list is a process-wide `StateFlow` (`CallNotesApp.callList`, `stateIn` Eagerly,
null = not loaded yet) so returning to the list never renders an empty frame. List ↔
detail is one `AnimatedContent` (slide + fade); tabs crossfade; the window background
(`values[-night]/themes.xml`) matches the Compose theme. Manual "transcribe now" calls
`TranscriptionService.start` directly — previously it went through the auto-transcribe
gate and did nothing when that switch was off.

### 2026-10-21 — Active model = installed model only (0.3.2)
Bug: tapping "switch to E2B" set the active id to a model that wasn't downloaded, so
`readyModel()` returned null and transcription silently never ran. Now the active
model only changes to an installed one, or after a user-started download verifies;
an uninstalled saved choice falls back to any installed model. Each transcription run
writes a one-line outcome (`Prefs.lastProcessing`) shown in the transcription card.

### 2026-10-21 — UI redesign (0.3.3)
Fixed "ink on paper" palette (green brand, warm neutrals, amber for in-progress) instead
of dynamic colour; bottom navigation with two tabs; call list grouped by day with the
first transcript line; a call detail screen (player, transcript that highlights the
playing segment and seeks on tap, technical details folded away). The settings tab
reuses the 0.3.x setup/permission/switch sections unchanged (`ui/SettingsSections.kt`,
only the containers restyled) — writing a new settings screen was stopped by a safety
filter in the session, so the existing, already-shipped code was moved instead.

### 2026-10-20 — 0.3 transcription design choices
- **Model download via DownloadManager** into the app-specific external dir (resumes,
  system progress notification, Wi-Fi-only by default), then SHA-256 check before use.
  `INTERNET` permission added for this only.
- **CPU backend**, greedy decoding, thinking off, one conversation per 28 s chunk.
- **Kotlin 2.4** because LiteRT-LM 0.17.1 ships Kotlin 2.4 metadata.
- **Audio import** (any format) so the pipeline can be tested without calls; imports
  get `kind = "import"`.
- **No crash loops:** a call left in `transcribing` at the start of a run is marked
  failed (not retried), since a native crash/OOM would otherwise repeat every launch.
- Transcription starts from `finish()` while the mic foreground service is still up
  (background FGS starts can be refused); the UI also kicks the queue on resume.
- Owner chose to continue development while unable to make test calls; the 0.2.3
  screen-lock fix is untested.

### 2026-10-20 — Record inside a microphone foreground service
Capture went silent when the screen locked during a call. The standard, visible fix:
a `microphone`-type foreground service for the length of each recording, with an
ongoing notification. Chosen over any approach that would hide the recording from the
user; the notification and the system mic indicator stay.

### 2026-10-20 — Record internet calls (experimental), no names yet
Owner asked for WhatsApp recording. Detected via audio mode (no permission), recorded
by the same accessibility-service capture. Names deferred: they'd need notification
listener access (reads all notifications) — owner chose "no names for now". Default
on; pulled forward from "after 1.0".

### 2026-10-20 — Minimal permissions (0.2)
The owner asked to drop everything recording doesn't use. The 0.1 test showed both
sides are captured without speakerphone, so:
- **Required:** accessibility service (with no event types and
  `canRetrieveWindowContent=false` — it can't read the screen), `RECORD_AUDIO`,
  `READ_PHONE_STATE`.
- **Optional:** `READ_CALL_LOG` (number, cached name, direction, unanswered-call
  cleanup), `POST_NOTIFICATIONS`. App records fine without them.
- **Removed:** `READ_CONTACTS` (the call log's cached name suffices) and the
  speaker-button tap (needed window content).
- **Kept, invisible to the user:** `MODIFY_AUDIO_SETTINGS` (normal permission) for an
  opt-in auto-speaker, default off, for devices whose far side turns out quiet.
- Since the service no longer receives events, the UI calls
  `CallRecorderService.refreshFromUi()` on resume to start call detection if
  `READ_PHONE_STATE` was granted after the service connected.
Possible further step: detect calls from `AudioManager` mode (`MODE_IN_CALL`, no
permission) instead of `READ_PHONE_STATE` — untested, not done.

### 2026-10-19 — Build APKs in GitHub Actions, install from Releases
Owner wants to install without developer options or Android Studio. CI builds every
push and publishes a prerelease. Side benefit: CI is the compile check for sessions
that can't download Android dependencies. Fixed debug keystore committed so updates
install in place.

### 2026-10-19 — Path A: accessibility service + automatic speakerphone
On Google-dialer phones there is no legitimate way to get both sides at full quality.
Chosen: record the mic from an accessibility service (the only exemption from
in-call mic silencing) and move the call to the loudspeaker (API → tap the in-call
speaker button → ask the user). Rejected: automating the dialer's Share flow (screen
takeover, owner rejected it), other system apps, fake Bluetooth, root. Deferred past
1.0: VoIP mode, Bluetooth accessory.

### 2026-10-19 — Owner's phone runs path A
POCO X6 Pro uses Google's dialer, whose recordings aren't in shared storage
(see devices.md). Path A is built first; path B moves to 0.7.

### 2026-10-19 — Skip the standalone Hebrew feasibility test
Measure inside the app instead: keep original audio, make models swappable, add a
side-by-side comparison screen. If Gemma's Hebrew transcription is weak, swap the
Transcriber (e.g. ivrit.ai Whisper) without touching the rest.

### 2026-10-19 — Separate, user-swappable Transcriber and Analyzer
The owner wants users to be able to change the analysis model. Two interfaces, a model
catalog, per-call record of which models ran.

### 2026-10-18 — Gemma 4 for both transcription and analysis; E4B with E2B fallback
Gemma 4 E2B/E4B are the only on-device Gemma models with audio input. Processing
happens after the call in the background, so latency matters less than quality →
E4B where RAM allows. 30 s audio limit → 28 s chunks with 3 s overlap.

### 2026-10-18 — Models downloaded at setup, not bundled
Multi-GB APKs are impractical, the model differs per device, and users can swap it.

### 2026-10-18 — Not AICore / Gemini Nano
Inference there is allowed only for the top foreground app; we need background
processing, and device coverage is limited.

### 2026-10-18 — Android only
iOS gives third-party apps no access to cellular call audio.
