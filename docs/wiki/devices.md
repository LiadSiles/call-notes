# Device findings

One section per device actually tested. Record what was observed, not what was
expected. Newest first.

## POCO X6 Pro — HyperOS memory limit (2026-10-29)

0.22.0/0.22.1 log: every summary attempt of one call ended with
`[FORCE STOP] stop com.callnotes due to used too many pss resource, pss used: 8.5 GB,
pss threshold: 6291456kb` (6 GiB), about 110 s into the first answer, on screen or in the
background, debug and release builds alike. A force stop takes the app off the enabled
accessibility services — this, not Play Protect or the build type, is why accessibility
"reset" again and again. The summary then restarted on the next open: a loop. Earlier
summaries on this phone (25–27.10) finished; why this one went past 6 GB is not known yet
(0.22.2 logs the working copy LiteRT-LM keeps in the cache folder and the peak memory).
0.22.2: the guard stopped a 3-line summary at 6.0 GB; the storage page showed LiteRT-LM's
working copy present (2.3 GB) next to the 3.7 GB model file → 0.23.0 releases the model
file's pages near the line.
0.23.1: the memory is the model's own buffers (5.5–6.1 GB anonymous), not the files.
"[FORCE STOP] … from process:com.miui.securitycenter" (Security app cleanup) also stops
the app and turns accessibility off.
0.24.x, POCO X6 Pro (Mali GPU): Gemma E4B **on the GPU** finished one summary at 1.3 GB
of app memory, but loading took 15–35 s, free RAM fell to ~0.8 GB, the UI froze ("Input
dispatching timed out", the owner felt the whole phone hang) and once the app crashed —
the GPU switch then turned itself off as designed. On the CPU with thinking off (context
8192): 5.8–5.9 GB within ~20 s (working copy 0.8, the rest 4.7–4.9 GB) — just over the
guard. Gemma 4 E2B is the remaining option on this phone.
"OneKeyClean" (HyperOS one-tap clean) is a cleanup like "SwipeUpClean".
"SwipeUpClean" (clearing recent apps) also kills the app with its foreground service.
`mediaProcessing` foreground services hit Android's daily time limit ("Time limit already
exhausted"); the fallback to `dataSync` works.

## POCO X6 Pro — HyperOS 3.0.304.0 (owner's phone)

_Observed 2026-10-19, before installing 0.1._

- Dialer: **Google Phone** (not Xiaomi's dialer).
- Google dialer call recording exists, but **no "record all calls"** option — only
  "numbers not in contacts" and "selected numbers". Announces the recording to the
  other side.
- Its recordings are visible **only inside the dialer**; nothing in
  `MIUI/sound_recorder/call_rec`, `Recordings`, or the file manager's Audio category.
  Xiaomi's own FAQ mentions `call_rec` for HyperOS + Google dialer, but on this device
  it isn't used. → **No path B on this phone; it runs path A.**
- RAM ≥ 8 GB (all X6 Pro variants) → Gemma 4 E4B tier.

### 0.1 test results (2026-10-20)

- Install: Play Protect blocked the APK at first; installed after allowing it.
  Accessibility service was blocked by **restricted settings** until App info → ⋮ →
  "Allow restricted settings" (only appears after one attempt to enable the service).
- **Recording works, both sides clearly audible — with and without speakerphone.**
  On this device the accessibility-service mic capture during a call includes the far
  side at good quality without the loudspeaker. → Auto-speaker is not needed here;
  made opt-in (default off) in 0.2 and the speaker-button tap was removed.
- 0.2.1: WhatsApp calls record with the far side audible. **Locking the screen
  mid-call made the capture silent** until the call ended → fixed attempt in 0.2.3
  (microphone foreground service); awaiting confirmation.
- 0.3.0 call (2026-10-20): still **intermittent silent stretches**, with speakerphone on
  throughout, screen partly locked / partly on. Type of call (cellular/WhatsApp) not
  stated. Cause unknown → 0.3.1 adds a per-call trace; awaiting one.
- Not yet reported: which source chip showed (`voice_recognition`/`mic`), reboot
  survival, Bluetooth headset behaviour.

## Template

```
## <Model> — <OS version>
_Observed <date>, app version <x.y>._
- Dialer:
- Path:
- Speaker via API / tap / neither:
- Far side audible on speaker / earpiece:
- Background survival:
- Notes:
```

## POCO X6 Pro — speaker separation (2026-10-21)

Handset call (phone at the ear), 0.5.1: pyannote + WeSpeaker put the whole call under
one speaker. The other side only leaks from the earpiece into the mic, far quieter than
the owner's voice; segmentation mostly hears the owner, the forced 2-way clustering
splits that one voice and the merge rule (cosine ≥ 0.75) joins it back. → 0.5.2 uses
loudness for such calls.

Whisper speed on the POCO (0.5.1 log, 5 performance threads, beam 5, f16): 145 s of
audio in 166–192 s; 62 s in 102–114 s; an 828 s call reached 61 % after 800 s — roughly
1.1–1.3× the call's length. Candidates if that's too slow: greedy decoding, a quantized
(q8/q5) build of the ivrit.ai model.
