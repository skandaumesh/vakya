# Vakya

Reply suggestions in your own texting style, right inside WhatsApp. Tap the ✨ bubble above the message box and pick one of 3 replies.

## Run and test (Windows)

**1. Add your free AI key (once)**
Get a key at [console.groq.com/keys](https://console.groq.com/keys). Open `backend\.env` in Notepad, paste it after `GROQ_API_KEY=` and save.

**2. Check everything works:** double-click `backend\test.bat`.
It runs the offline tests and costs nothing. You should see `47 passed`.

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

## Limits

Groq's free tier allows about **100 suggestions a day**. When that runs out, the app says when to try again. Other free and paid providers are listed in [backend/README.md](backend/README.md).

## More detail

- [backend/README.md](backend/README.md): providers, server API, quality eval
- [android/README.md](android/README.md): app internals and code map
