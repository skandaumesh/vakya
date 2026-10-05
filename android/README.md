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

## Code map

| File | What it does |
|---|---|
| `service/ChatReaderService.kt` | Accessibility service: places the bubble, reads the chat, calls the API, types the reply, learns from sent messages |
| `service/ChatReader.kt` | Turns the accessibility tree into a chat title and messages (WhatsApp view ids, otherwise screen position) |
| `service/Overlay.kt` | Bubble and suggestion panel windows |
| `data/Store.kt` | On-device storage: style card, relationship, memory and examples for each contact |
| `data/ReplyApi.kt` | Backend client |
| `importer/ChatExportParser.kt` | WhatsApp export parser, a port of `backend/app/chat_export.py` |
| `ui/MainActivity.kt` | Setup, style import and the per-contact memory screen |
