# Vakya

Two things in one Android app:

- **Reply suggestions** in your own texting style, right inside WhatsApp. Tap the ✨ bubble above the message box and pick one of 3 replies.
- **Friday**, a voice assistant for the phone. Say "Hey Friday" (heard on the phone, nothing sent until then) and she answers, remembers, looks at your screen when you ask, and runs several steps at once: "message Amma I'll be late and remind me at 8", "I'm going to sleep", "turn on Bluetooth and play my liked songs", "what does this error mean?". She asks before routines and sending, checks each step really happened, and says so when one didn't. The app opens on Friday's **Command Center** (phone status, what's coming up, memory, routines, permissions).

## Run and test (Windows)

**1. Add your free AI key (once)**
Get a key at [console.groq.com/keys](https://console.groq.com/keys). Open `backend\.env` in Notepad, paste it after `GROQ_API_KEY=` and save.

**2. Check everything works:** double-click `backend\test.bat`.
It runs the offline tests and costs nothing. You should see `269 passed`.

**3. Try replies on your PC:** double-click `backend\try.bat`.
Type a message you received and press Enter to get 3 replies. Commands: `/rel client`, `/style short`, `/send 1`, `/quit`.
To copy your real style, export a WhatsApp chat (⋮ → More → Export chat → Without media), put the `.txt` in `backend\`, then in a terminal in that folder:

```
try.bat --export "WhatsApp Chat with Rahul.txt" --rel friend
```

**4. Use it on your phone**
1. On the phone, open Settings → About phone and tap **Build number** 7 times. Then go to Developer options and turn on **USB debugging**.
2. Double-click `backend\server.bat` and leave the window open.
3. Connect the phone by USB and double-click `android\phone.bat`. Tap **Allow** on the phone if it asks.
4. On the phone, open Vakya:
   - set the server to `http://localhost:8000`, then tap **Save and test**
   - tap **Turn on in Accessibility settings** and enable Vakya
5. Open a WhatsApp chat, tap the message box and tap ✨.

**5. Use it on a laptop (WhatsApp Web)**
Run `backend\.venv\Scripts\python desktop\build.py`, and load the `release\Vakya-Desktop` folder it makes at `chrome://extensions` → Developer mode → **Load unpacked**. Details are in [desktop/README.md](desktop/README.md).

## Friday on the phone

After installing, open Vakya. The Command Center lists every permission with why it's needed; each is optional:

- **Microphone** for "Hey Friday", and **Accessibility** for seeing and tapping the screen, Quick Settings switches and typing.
- **Do not disturb** and **Modify system settings** let her switch Do not disturb, brightness and auto-rotate directly (otherwise she uses Quick Settings).
- **Calendar**, **Notification access**, **Contacts** and **App usage** for "what's on today?", "any new messages?", "call Amma" and "screen time".

Android lets no app switch Wi-Fi, Bluetooth, mobile data or battery saver directly, so Friday flips their Quick Settings tile and reads the result back; if a phone's tile has another name, she opens the setting for you instead.

Friday's memory (about you, tasks, routines, your recent talks with her) stays on the phone. Only the few items that match a question go to the AI, and the server keeps nothing. Export or delete it all in the Command Center.

## Limits

Groq's free tier allows about **100 suggestions a day** (about 50 Friday commands, which use more of it). When that runs out, the app says when to try again. Other free and paid providers are listed in [backend/README.md](backend/README.md).

## More detail

- [backend/README.md](backend/README.md): providers, server API, quality eval
- [android/README.md](android/README.md): app internals and code map
- [desktop/README.md](desktop/README.md): WhatsApp Web extension (install, build, test)
