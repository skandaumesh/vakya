# Vakya for desktop (WhatsApp Web)

A browser extension for Chrome, Edge and Brave. On [web.whatsapp.com](https://web.whatsapp.com), a round Vakya button sits above the message box. Clicking it (or pressing **Alt+V**) shows 3 replies to their newest messages. If your own message is the last one, it suggests follow-ups instead. Clicking a reply, or pressing **Alt+1/2/3**, types it into the box. You still press Enter to send.

**Write it for me:** type what you want to say in your own words first ("ask him if he's coming tomorrow"), then press Alt+V. You get it written 4 ways: My style, Professional, Short and Gen Z (Alt+1 to 4). The one you pick replaces what you typed.

It uses the same server, styles and daily limit as the phone app.

## Install (for you and friends)

1. Right-click `release/Vakya-Desktop-0.4.0.zip` → **Extract All**. You get a `Vakya-Desktop-0.4.0` folder with `manifest.json` inside; keep it somewhere it won't be deleted.
2. Open `chrome://extensions` (Edge: `edge://extensions`, Brave: `brave://extensions`) and turn on **Developer mode**.
3. Click **Load unpacked** and pick that folder (the one with `manifest.json` in it).

On the computer you build on, load `release/Vakya-Desktop` instead: `build.py` refreshes that folder each time, so after a rebuild you only click the reload icon on the Vakya card.
4. Pin Vakya from the puzzle-piece menu. Click its icon to open settings, which should say "Connected".
5. Reload WhatsApp Web (F5) and open a chat.

The first reply of the day can take up to a minute, because the free server wakes up from sleep.

## Build

```
backend\.venv\Scripts\python desktop\build.py
```

This does four things:
- writes `extension/config.js` (server address and app key) from `android/vakya.properties`;
- makes the icons from `android/logo.png`;
- copies the font;
- zips everything to `release/`.

`config.js` is git-ignored. Without it, set the server in the extension's settings ("Advanced: server").

## Test

- `node --test desktop/tests/shared.test.js` tests the chat-export parser, the "who is me" guess, the memory rules and the placeholder check.
- `desktop/tests/mock-whatsapp.html` is a stand-in for WhatsApp Web that runs the real `content.js` with a fake server. Open it in a browser with `#open` (or `#dark`, `#group`, `#pick`) after the address. To render it headless:

  ```
  msedge --headless=new --allow-file-access-from-files --window-size=1280,800 --virtual-time-budget=2000 --screenshot=panel.png "file:///E:/Desktop/replybot/desktop/tests/mock-whatsapp.html#open"
  ```

  Use `--dump-dom` with `#pick` to see the request it sent and what landed in the message box. The JSON is in `<pre id="check">`.

## When WhatsApp Web changes

All page reading is in `content.js` under "Reading WhatsApp Web":

| What | How it's found |
|---|---|
| Message box | `#main footer [contenteditable="true"]` |
| Chat name | first `span[title]` / `span[dir=auto]` in `#main header` that isn't "online"/"typing…" |
| Who sent a message | `.message-in` / `.message-out`; if those classes disappear, it falls back to bubble position (right = me) |
| Sender name and time | `[data-pre-plain-text]`, which looks like `[10:15, 05/10/2026] Rahul: ` |
| Text | `.selectable-text` |
| Photos and stickers | `img[src^="blob:"]` wider than 60px |
| Dark mode | `body.dark` |

The reply is put in with `document.execCommand("insertText")`, which WhatsApp's editor accepts like typing. If that fails, it falls back to a paste event. The extension never presses Send.

## Files

- `manifest.json`: Manifest V3. Permissions are `storage`, WhatsApp Web, and the server.
- `background.js`: all server calls (so the app key stays out of the WhatsApp page, and there are no CORS problems).
- `content.js` / `content.css`: the button, the panel, and reading and writing the chat.
- `options.html` / `options.js`: settings, style import from a chat export, your own Groq key, and remembered chats.
- `shared.js`: logic shared with the tests (export parser, a port of `backend/app/chat_export.py`).

Your style card and per-chat memory are kept in `chrome.storage.local` on that computer only.
