# Device findings

One section per device actually tested. Record what was observed, not what was
expected. Newest first.

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
