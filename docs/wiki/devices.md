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

### 0.1 test results

_Pending._ Fill in per test from the README test plan: speaker label, source label,
"silence" flag, far-side audibility, survives reboot?

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
