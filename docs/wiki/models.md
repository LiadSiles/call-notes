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
