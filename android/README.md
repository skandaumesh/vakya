# Vakya Android app

A ✨ bubble appears above the message box in WhatsApp, Telegram, Instagram and Google Messages. Tap it and Vakya reads the chat on screen, then shows 3 replies labelled by action (Accept, Delay, Clarify and so on). Tap one and it's typed into the box; you still press Send.

## Build and install

```bash
cd android
./gradlew assembleDebug testDebugUnitTest      # needs JDK 17+, e.g. Android Studio's jbr
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connect it to the backend

Start the backend on your computer (see `../backend/README.md`), then:

- **Real phone over USB:** run `adb reverse tcp:8000 tcp:8000`, and in the app set the server to `http://localhost:8000`.
- **Emulator:** the default `http://10.0.2.2:8000` already points at your computer.

Plain HTTP is only allowed for these local addresses. A deployed server must use HTTPS.

## First run

1. Open Vakya and tap **Turn on in Accessibility settings**. Read the disclosure, then enable Vakya.
2. **Save and test** the server address.
3. Optional: **Import a WhatsApp chat** (WhatsApp → chat → ⋮ → More → Export chat → Without media) to teach it how you text.
4. Open a chat and tap ✨.

In the panel, the pill at the top sets who this person is (friend, client, professor and so on). The row below switches style: Mine, Pro, Short or Friendly. Notes Vakya remembers about each contact (promises, deadlines, prices) appear on the main screen; long-press a note to delete it.

## Friday (0.9)

The launcher opens `ui/CommandCenterActivity`. Friday's flow for one command:

1. `jarvis/JarvisService` hears "Hey Friday" on the phone (`FridayWake`, sherpa-onnx), records the command and sends it with context: the screen's chat, phone status, calendar, tasks, memory (searched on the server) and routines.
2. The server answers with an action. `device` brings steps to run (up to 6). `confirm: true` makes Friday ask first and hold the steps until "yes". `look` means she needs to see the screen: a screenshot and the screen's words go back with the question.
3. `agent/TaskRunner` runs the steps one by one through `jarvis/DeviceActions`. It waits for apps to open, checks each step (an app really came to the front, a switch really changed), and skips steps that depended on a failed one. A failed tap triggers one look-and-retry. Friday then says what really happened.
4. `agent/FridayBus` carries her state to every view: her card at the top of the screen (over any app; it needs Accessibility on), the floating orb and the Command Center. Friday never opens a screen of her own.

Say "Friday" while she's talking to interrupt her, or press stop.

## Code map

| File | What it does |
|---|---|
| `service/ChatReaderService.kt` | Accessibility service: places the bubble, reads the chat, calls the API, types the reply, learns from sent messages; Friday's floating orb, HUD and screen access |
| `service/ChatReader.kt` | Turns the accessibility tree into a chat title and messages (WhatsApp view ids, otherwise screen position) |
| `service/Overlay.kt` | Bubble and suggestion panel windows |
| `data/Store.kt` | On-device storage: style card, relationship, memory and examples for each contact |
| `data/ReplyApi.kt` | Backend client |
| `importer/ChatExportParser.kt` | WhatsApp export parser, a port of `backend/app/chat_export.py` |
| `ui/MainActivity.kt` | Reply assistant settings: style import, server, per-contact notes, voice, privacy |
| `ui/CommandCenterActivity.kt` | Friday's home: status, device tiles, up next, notifications, memory, routines, proactive settings, permissions |
| `ui/OrbView.kt`, `ui/Neo.kt` | The animated orb and the dark look's view builders |
| `jarvis/JarvisService.kt` | Wake word, recording, requests, confirm/look/retry flow, interrupting, briefings, reminders and alerts |
| `jarvis/DeviceActions.kt` | Each step kind (alarm, call, message, play, toggle, tap, task, calendar...), reporting a checked `Outcome` |
| `agent/TaskRunner.kt` | Runs steps in order, checks them, writes the honest summary |
| `agent/FridayBus.kt` | What Friday is doing, for every view |
| `device/SystemControls.kt` | Switches, brightness and volume: Android API where allowed, else Quick Settings, then read back |
| `device/DeviceStatus.kt`, `device/CalendarReader.kt` | Phone state, upcoming events |
| `accessibility/QuickSettings.kt` | Finds and flips a Quick Settings tile by name; sets the brightness slider |
| `accessibility/ScreenAgent.kt` | Screen words, tap by label, type, switch to the last app |
| `vision/ScreenLook.kt` | The screenshot, only when asked (Android 11+) |
| `memory/Memory.kt` | Tasks, routines, conversation log; search, export, delete |
| `automation/Proactive.kt` | Morning briefing and evening summary alarms, notifications |
| `permissions/Permissions.kt` | Every permission, why, and how to allow it |
