# Models

_Last verified: 2026-10-20 (code at 0.3.0)._

## Status (0.3.0)

Transcription is implemented; analysis (summary/tasks) is 0.4.

| Piece | Where |
|---|---|
| Catalog with pinned files | `inference/Models.kt` → `ModelCatalog` |
| Device choice, download, SHA-256 check | `inference/ModelManager.kt` |
| Any audio → 16 kHz mono WAV | `audio/AudioDecoder.kt` |
| 28 s / 3 s chunking at quiet points | `audio/Chunker.kt` |
| Gemma via LiteRT-LM | `inference/GemmaTranscriber.kt` |
| Overlap merge, loop detection, announcement filter | `inference/TranscriptMerger.kt` (+ unit tests) |
| Queue in a foreground service | `processing/TranscriptionService.kt` |

Pinned files (litert-community, not gated, Apache-2.0, checked 2026-10-20):

| Model | File | Bytes | Min RAM reported |
|---|---|---|---|
| Gemma 4 E4B | `gemma-4-E4B-it.litertlm` | 3,659,530,240 | 7.0 GB |
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm` | 2,588,147,712 | 5.0 GB |

SHA-256 values are in the catalog. The repos also have `-gpu`, `-web` and
chip-specific (Tensor G5/G6, Qualcomm sm8750…) variants — unused for now.

### LiteRT-LM facts we rely on (v0.17.1, `com.google.ai.edge.litertlm:litertlm-android`)

- Built with **Kotlin 2.4** → the project must use Kotlin ≥ 2.4 (KSP 2.3.11).
- `EngineConfig(modelPath, backend, audioBackend, maxNumTokens, cacheDir)`; audio needs
  `audioBackend` set or no audio executor is created. `initialize()` takes seconds —
  always off the main thread.
- `Content.AudioFile(absolutePath)` — we pass a 16 kHz mono PCM16 WAV per chunk.
- `ConversationConfig(systemInstruction, samplerConfig, maxOutputToken, thinkingConfig)`;
  we use greedy `SamplerConfig(topK=1, topP=1.0, temperature=0.0)` and
  `ThinkingConfig(enableThinking=false)`. A fresh conversation per chunk.
- `Message.toString()` = concatenated text contents (thinking goes to `channels`).
- Prompt (0.3.8): Gemma's audio docs give the ASR template "Transcribe the following
  speech segment in {LANG} into {LANG} text." + formatting rules (no newlines, digits
  for numbers); text item before the audio item; 30 s max per clip; 16 kHz mono;
  25 audio tokens per second. 0.3.0–0.3.7 used a free-form prompt after the audio plus
  a system instruction — Hebrew output was poor (invented words, misspellings).
- Each chunk is level-normalized (`audio/AudioLevel`, ≈ −20 dBFS speech, pauses
  excluded, peak-limited) in the model's temp copy only.
- Gemma 4's model card names no speech languages (just "35+ supported, 140+
  pre-trained") and reports no per-language ASR numbers; Hebrew quality is unverified.
- **CPU backend** on purpose: community reports say GPU init crashes on phones with
  < 12 GB RAM. E4B on CPU ≈ 3.3 GB RSS (S26 Ultra figure).
- APK is limited to `arm64-v8a` to keep the native libraries small.

Facts about Gemma 4 below come from Google's model cards and the LiteRT-LM docs.

## Transcription: ivrit.ai Hebrew Whisper on whisper.cpp (0.4)

| Piece | Where |
|---|---|
| Catalog entry (pinned commit 2130c78, SHA-256, 1,624,555,275 bytes, Apache-2.0) | `ModelCatalog.WHISPER_HE_TURBO` |
| Native build (whisper.cpp v1.9.5 submodule, static, JNI) | `app/src/main/cpp/`, `third_party/whisper.cpp` |
| Kotlin JNI surface, CPU feature gate | `inference/whisper/WhisperNative.kt` |
| Parse result (UTF-8 bytes → words with times) | `inference/whisper/WhisperOutput.kt` (+ tests) |
| Transcriber | `inference/whisper/WhisperTranscriber.kt` |

Facts and choices:
- File is f16 ggml (1.6 GB). No quantized Hebrew build is published; quantizing
  ourselves is a later option (q5/q8 ≈ 0.6–0.9 GB, faster).
- Build: `GGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod`; the app refuses to load the lib
  unless `/proc/cpuinfo` lists `asimddp`, `fphp`, `asimdhp` (illegal-instruction guard).
  Release flags are forced even in the debug APK (`-DCMAKE_BUILD_TYPE=Release`).
  `GGML_OPENMP=OFF` (no libomp to ship). CI fails if `libcallnotes_whisper.so` is
  missing from the APK.
- Params: language `he`, beam search 5, `no_context` (one bad window can't derail the
  rest), `suppress_nst`, token timestamps + DTW (`WHISPER_AHEADS_LARGE_V3_TURBO`,
  enum value 14 — DTW needs flash attention off). Threads = performance cores
  (`CpuInfo.inferenceThreads`, cores faster than the slowest; 2–8).
- JNI returns text as raw UTF-8 bytes: BPE tokens can split a 2-byte Hebrew letter and
  `NewStringUTF` aborts on invalid input. Words = tokens joined until a token starting
  with a space; word start = DTW time when available.
- Cancellation: a watcher coroutine calls `abort()` → whisper's abort callback.
- Model files per role: `Prefs.modelFor(role)`; the analysis role keeps the old
  `model_id` key (the user's Gemma choice).

## Who said what: sherpa-onnx diarization (0.4.1)

| Piece | Where |
|---|---|
| Runtime: sherpa-onnx v1.13.8 prebuilt `libsherpa-onnx-jni.so` + `libonnxruntime.so` (arm64) | fetched by `scripts/fetch-sherpa-onnx.sh` (pinned SHA-256) into `app/src/main/jniLibs/` (git-ignored); CI runs it before the build |
| Kotlin API (verbatim copy, Apache-2.0; JNI looks fields up by name — never rename) | `app/src/main/java/com/k2fsa/sherpa/onnx/` |
| Models: pyannote segmentation-3.0 ONNX (MIT, 6 MB) + WeSpeaker ResNet34-LM VoxCeleb (CC-BY-4.0, 26.5 MB) — HF mirrors by the sherpa-onnx author, pinned commits + SHA-256 | `ModelCatalog.SPEAKER_PACK`, downloaded as one pack (`ModelManager.startDownloads`) |
| Diarize (2 clusters), per-speaker fingerprint from its longest turns (≤ 20 s), merge if cosine ≥ 0.75 | `inference/speakers/SpeakerDiarizer.kt` |
| Words → speaker by max overlap (nearest turn ≤ 1 s, else previous); line per speaker run, new line after 2.5 s pause | `inference/speakers/SpeakerAligner.kt` (+ tests) |
| Owner voice sample (MIC, 8–20 s) → fingerprint in `files/voice_profile.bin`; `whichIsMe` = cosine ≥ 0.35 and ≥ 0.10 margin | `inference/speakers/Voice.kt`, `VoiceProfile.kt`, `ui/SpeakerSection.kt` |

- DB v5: `segments.speaker` (0/1/null), `calls.speakerEmbeddings` (2×dim LE floats),
  `calls.meSpeaker`. A new/deleted voice sample re-labels every call from the stored
  fingerprints (`VoiceProfile.relabelAll`) — no re-run.
- **Never use `processWithCallback`** (0.4.1–0.5.0 crash on the POCO): sherpa's JNI
  looks up `invoke(IIJ)Ljava/lang/Integer;` on the callback object's class. Kotlin 2
  compiles lambdas with invokedynamic (D8 `$$ExternalSyntheticLambda`), which only has the
  erased `invoke(Object,Object,Object)`; the lookup throws NoSuchMethodError and ART's
  CheckJNI aborts the process ("JNI GetObjectClass called with pending exception").
  We call `process` (no progress, can't be aborted — it takes seconds).
- General rule for any third-party JNI that calls back into Kotlin: pass an object of a
  named class or a `fun interface` declared with the exact signature, never a plain lambda.
- Thresholds (0.75 same-voice, 0.35/0.10 owner match) are first guesses; the log
  prints the two voices' similarity and the "me" decision per call to tune them.
- A failure in speaker separation keeps the plain transcript (logged).

### Two methods, chosen per call (0.5.2)

1. **Loudness** (`inference/speakers/LevelSpeakers.kt`, + tests) — first choice. Word
   level = RMS over the word ±40 ms in dBFS; weighted 2-means; accepted if the clusters
   are ≥ 7 dB apart and each side has ≥ 6 % of the speech time. Two-state Viterbi with a
   Gaussian emission (pooled sd, floor 3 dB) and a switch probability that grows with the
   pause before the word (0.03 < 250 ms … 0.45 ≥ 1.5 s). Louder = me; with a voice sample
   and the speaker pack, the two sides' fingerprints may swap it. No models needed; no
   fingerprints stored (`relabelAll` leaves these calls alone).
2. **Voice fingerprints** (`VoiceClusters` + `VoiceEmbedder`, 0.5.3) — when loudness
   doesn't separate (speakerphone, VoIP). Whisper words → windows (split at > 400 ms
   pauses, ≤ 3 s); windows ≥ 1 s of loudness-equalised audio get a WeSpeaker
   fingerprint; weighted 2-means on cosine (seeds: longest window + the one least like
   it); one voice if centroids ≥ 0.75 alike or a side has < 5 %; two-state Viterbi
   (emission 8 × cosine margin; switch p 0.3 after a pause, 0.08 otherwise); windows
   without a fingerprint follow their neighbours. Centroids are stored per call.

**sherpa-onnx's `OfflineSpeakerDiarization` is not used any more** (0.5.3): its native
code calls `exit(-1)` on edge cases (`offline-speaker-diarization-pyannote-impl.h`:
"This segment is too short…"), which killed the app 2–5 s into speaker separation on
the POCO. Only `SpeakerEmbeddingExtractor` is used, with length and `isReady` checked
in Kotlin before `compute`. pyannote segmentation is no longer downloaded
(`ModelCatalog.RETIRED`, file deleted on start).

The log line says which method ran and why (levels, gap, shares, voice-sample scores).

## Summary and tasks: Gemma 4 text (0.5.0)

| Piece | Where |
|---|---|
| Prompt (call date + weekday, who is who, transcript ≤ 12k chars keeping start+end, JSON schema, rules) and tolerant JSON parsing, due-date validation (call date −1 day … +2 years) | `inference/AnalysisPrompt.kt` (+ tests, real org.json in tests) |
| Model call: LiteRT-LM `Engine` text only (no audio backend), CPU, `maxNumTokens` 8192, greedy, thinking off, ≤ 1536 output tokens | `inference/GemmaAnalyzer.kt` |
| Pass: after all transcriptions, Whisper + diarizer are closed first (memory), then requested calls, then (auto on) newest unanalysed | `TranscriptionService.analysePending` |
| Storage: `calls.analysisStatus/analysisJson/summaryTitle/analysisModel/analysisError`, `tasks` table (DB v6) | `data/` |

- **Hebrew gotcha (0.5.4):** Gemma writes acronyms with an ASCII `"` as gershayim
  (`נתב"ג`), which ends the JSON string early ("Unterminated object"). The prompt asks for
  ״ / ׳, and `AnalysisPrompt.repairQuotes` fixes answers that still do it: letter-`"`-letter
  → ״, then any `"` inside a string not followed by `,` `:` `}` `]` is escaped.
- A new transcript clears the old analysis (`CallDao.replaceTranscript` → `clearAnalysis`).
- The model's answer is call content: the log records only sizes, counts and timings.
- Speaker labels in the prompt: "אני" / contact name when the owner is known, else
  "דובר 1/2" and the prompt says ownership is unknown.

## Default: Gemma 4, one model for both roles (0.3 — superseded for transcription)

- Family: E2B, E4B, 26B A4B, 31B. **Only E2B and E4B** run on phones and have
  **native audio input** (speech recognition / translation). 128K context.
- E2B ≈ 2.3B effective params, ~2.6 GB as `.litertlm`; ~52 tok/s decode on a flagship
  Android GPU via LiteRT-LM.
- E4B ≈ 4.5B effective params; clearly better at reasoning and noisy audio; slower,
  larger, wants ~8 GB RAM.
- **Audio input is limited to ~30 s per clip** → chunking is mandatory.
- Selection: RAM ≥ 8 GB and ≥ 6 GB free storage → E4B; 6–8 GB RAM → E2B; less →
  unsupported. A short on-device benchmark after download may downgrade E4B to E2B.
- Runtime: **LiteRT-LM** (Kotlin API), GPU first, CPU fallback, NPU where supported.
- **Not AICore / ML Kit GenAI:** the system Gemini Nano only runs when the app is the
  top foreground app — incompatible with post-call background processing — and exists
  on a subset of devices.

## Pipeline (0.3–0.4)

1. Decode to 16 kHz mono PCM (0.1 already records in this format).
2. Chunk: 28 s window, 3 s overlap (25 s step); move each cut to the quietest point in
   the window's last 4 s (energy VAD); skip silent chunks.
3. Transcribe each chunk, temperature 0, prompt "transcribe verbatim in the spoken
   language, don't translate or summarize". Optionally pass the last 20–30 words of the
   previous chunk as context (check it doesn't cause repetition). Empty output or
   repeated n-grams → one retry, then mark the chunk unclear.
4. Merge overlaps by longest common word run after normalization (strip punctuation and
   niqqud), cut in the middle of the alignment; keep timestamps.
5. Filter the Google dialer's "call is being recorded" announcement if present.
6. Analyze: one pass if the transcript fits the budget (~8K tokens ≈ 15–20 min of
   talk), else map-reduce per ~10 min then merge and dedupe tasks.
7. Validate JSON against the schema (product.md); up to 2 retries with the error, then
   fall back to a plain-text summary.
8. Run in a foreground service (`mediaProcessing` on 15+, `dataSync` before); load the
   model once per queue, unload after; pause on `THERMAL_STATUS_SEVERE`.

## Swappable models (0.6)

Two interfaces in `inference/Models.kt`: `Transcriber` (audio → timestamped text) and
`Analyzer` (transcript → `CallAnalysis`). A catalog entry (`ModelSpec`) lists roles,
engine (`LITERT_LM`, `LLAMA_CPP`, `WHISPER_CPP`), URL, size, sha256, min RAM.

- A model without audio input can only be an Analyzer.
- Planned alternatives: Hebrew-tuned Whisper (ivrit.ai) via whisper.cpp for
  transcription; GGUF models via llama.cpp for analysis; manual GGUF import.
- Each call stores which models processed it; "reprocess with another model" and a
  side-by-side developer screen replace the skipped Hebrew feasibility test.
- Keep original audio (at least during development) so reprocessing is possible.

## Open questions

- Gemma 4 Hebrew transcription quality on real phone calls (no test yet — decided to
  measure inside the app instead).
- Exact `.litertlm` URLs, sizes and checksums for E4B; license check for redistribution.
