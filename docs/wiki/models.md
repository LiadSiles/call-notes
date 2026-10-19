# Models

_Last verified: 2026-10-19._ Facts about Gemma 4 come from Google's model cards and the
LiteRT-LM docs as of mid-2026; re-check sizes and URLs when pinning the catalog.

## Default: Gemma 4, one model for both roles

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
