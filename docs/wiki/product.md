# Product

_Last verified: 2026-10-19_

## Goal

After every phone call, without the user doing anything, the phone has:
the recording, a full transcript, a short summary with key points and decisions,
and suggested tasks with due dates — all produced locally.

## Scope

**In (1.0):** Android 10+; cellular calls; internet calls (WhatsApp etc., experimental); Hebrew and English; fully on-device
processing; tasks inside the app (from calls, or added by voice or typing) plus pop-up reminders with snooze and export to the device calendar.

**Out (1.0):** iPhone (no API to call audio); cloud processing;
speaker diarization beyond "me / the other side".

**After 1.0 (candidates):** VoIP calling mode (SIP trunk), names for internet calls,
diarization, a Bluetooth recording accessory.

## Users and setup

The user installs an APK, then completes a setup checklist on the main screen
(permissions, accessibility service, battery exemption, Xiaomi autostart; later:
model download and a test call). After that, recording is automatic.

## Output schema (analysis, from 0.4)

```json
{
  "title": "...", "summary": "2-4 sentences",
  "key_points": [], "decisions": [],
  "tasks": [{ "title": "", "owner": "me|other", "due_phrase": "", "due_date": "ISO",
              "confidence": 0.0, "source_quote": "" }],
  "events": [{ "title": "", "date": "", "time": "" }],
  "follow_ups": [], "language": "he"
}
```

`source_quote` lets the user see where a task came from and lets tests catch invented
tasks. Dates are recomputed in code relative to the call time; the model's date is a
hint. Default task mode is "suggest, user approves".

## Legal

Israel: recording a call you take part in is legal. The app recommends telling the
other side. Review with a lawyer before wide distribution; other countries may require
all-party consent.
