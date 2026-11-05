# Vakya backend

Small API that turns a chat snippet into 3 reply options in your own style. It keeps the AI key off the phone and stores nothing; logs contain sizes and timings, never message text.

## Pick a free AI provider

| `VAKYA_PROVIDER` | Cost | Get a key | Privacy | Notes |
|---|---|---|---|---|
| `groq` (default) | Free tier, no card | [console.groq.com/keys](https://console.groq.com/keys) | Not used for training; not kept by default | Very fast (~1.3 s). Model `openai/gpt-oss-120b`. Free caps: 200k tokens/day and 8k tokens/minute, which is about **100 suggestions a day** and 4 a minute |
| `gemini` | Free tier | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | **Free-tier prompts may be used by Google and read by human reviewers** | Fine for testing with your own chats, not for other people's |
| `ollama` | Free, runs on your PC | [ollama.com](https://ollama.com), then `ollama pull qwen2.5:7b` | Nothing leaves your computer | Needs about 8 GB of free RAM; weaker at Kanglish/Hinglish |
| `openai` | Depends | Any OpenAI-compatible API (OpenRouter, Cerebras, ...) | Depends | Set `VAKYA_BASE_URL`, `VAKYA_API_KEY`, `VAKYA_MODEL` |
| `anthropic` | Paid (under half a cent per suggestion) | [console.anthropic.com](https://console.anthropic.com) | Not used for training | Claude Haiku 4.5 |

Free tiers have daily limits. When one is hit, the app shows when to try again. To keep going, add a backup provider with its own allowance: set `VAKYA_FALLBACK_PROVIDERS=gemini` and `GEMINI_API_KEY`. Groq is still used first, and Gemini only when Groq is out of quota.

## Setup

```bash
cd backend
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt   # Windows
```

Set your key (PowerShell shown; Git Bash uses `export GROQ_API_KEY=...`):

```powershell
$env:GROQ_API_KEY="gsk_..."
# or, for Gemini:  $env:VAKYA_PROVIDER="gemini"; $env:GEMINI_API_KEY="..."
```

All options are listed in [.env.example](.env.example).

## Try it in the terminal

```bash
.venv/Scripts/python scripts/try_reply.py --rel client
```

With your real texting style: in WhatsApp open a chat, then **⋮ → More → Export chat → Without media**, and copy the `.txt` here:

```bash
.venv/Scripts/python scripts/try_reply.py --export "WhatsApp Chat with Rahul.txt" --rel client
```

The first run builds a style card from your messages and saves it as `<chat>.style.json` (git-ignored).

## Run the server

```bash
.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

| Endpoint | Body | Returns |
|---|---|---|
| `POST /suggest` | chat messages, relationship, style, style card, examples, memory, draft, `language` (`auto`, `english`, `kanglish`, `kannada`, `hinglish`) | intent, 3 `{label, text}` options, memory updates |
| `POST /compose` | `intent` (what I want to say, in rough words), plus the chat, relationship, style card, examples, memory | `kind`, `meaning`, and either the message once per style (`variants`: `mine`, `professional`, `short`, `genz`) or, when `intent` asks for something to send ("pickup line"), 4 labelled `ideas` |
| `POST /jarvis` | `audio` (base64 WAV of a spoken command) or `text`, plus what's on screen (`in_chat`, chat, style card, language, `local_time`) and Friday's context: `device` status, `calendar`, `tasks`, `memories` (searched here, only matches reach the AI), `routines`, `pending` steps, and when she looks, `screen_image` / `screen_text` (`looked`, `failed`) | `heard`, `action` (write, suggest, read, answer, device, look, confirm, cancel, end, ignore), `say` (spoken reply), `steps` (phone tasks in order, at most 6; `task` is the first, for older phones), `confirm` (ask before running), `compose` or `suggest` results, and with `voice` in the request, `audio` (base64 WAV of `say`) |
| `POST /speak` | `text`, `voice` | `audio`: base64 WAV of the line in Friday's natural voice (the greeting, a reminder); 503 when the voice isn't available, and the phone says it itself |
| `POST /style-card` | `{"my_messages": [...]}` (5+) | style card to store on the device |
| `GET /health` | | `{"ok": true, "model": "groq/openai/gpt-oss-120b"}` |

Set `VAKYA_APP_KEY` to require an `X-Vakya-Key` header.

Friday's system prompt is a core plus sections added only when their context is sent (screen, memory, schedule, routines, briefing, notifications, usage), to save tokens on free tiers. Common commands ("turn on wifi", "set brightness to 40", "yes" to pending steps, "what's on my screen?", "what time is it", and several of these joined with "and") are answered in `app/quick.py` without a model call.

### Friday's natural voice

With `voice` set (`diana`, `hannah`, `autumn`, `austin`, `daniel`, `troy`), `/jarvis` sends Friday's reply back already spoken, made by Orpheus on Groq (`canopylabs/orpheus-v1-english`, `app/voice.py`) right after the AI answers, so the phone needs no second request. Before it works, **the Groq account must accept the model's terms once** at [console.groq.com/playground?model=canopylabs%2Forpheus-v1-english](https://console.groq.com/playground?model=canopylabs%2Forpheus-v1-english). Until then, or when the voice is rate-limited, replies come without audio and the phone speaks with its offline voice; a voice problem never fails a reply. `VAKYA_TTS=0` turns it off, `VAKYA_TTS_TIMEOUT` (default 6 s) caps the wait, and `VAKYA_VOICE_DAILY_LIMIT` (default 300) caps `/speak` lines per phone on the shared key. Memory search (`app/recall.py`) is BM25 over the phone's items; nothing is stored on the server.

## Tests and eval

```bash
.venv/Scripts/python -m pytest              # offline, no API calls
.venv/Scripts/python eval/run_eval.py       # 30 live cases against your provider
.venv/Scripts/python eval/run_eval.py client chain   # only matching case ids
```

The eval checks each case automatically (3 distinct options, intent, language mix, no AI phrasing, memory use) and prints every reply so you can read them. Results go to `eval/results/`. On a free tier it runs 2 cases at a time and waits out per-minute limits; it stops at once if the daily cap is hit. A full run takes several minutes and uses about 60k tokens, so on Groq's free tier run it at most twice a day, or pass case ids to run only some.
