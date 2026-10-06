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
| `POST /suggest` | chat messages, relationship, style, style card, examples, memory, draft | intent, 3 `{label, text}` options, memory updates |
| `POST /compose` | `intent` (what I want to say, in rough words), plus the chat, relationship, style card, examples, memory | `meaning` and the message once per style: `mine`, `professional`, `short`, `genz` |
| `POST /style-card` | `{"my_messages": [...]}` (5+) | style card to store on the device |
| `GET /health` | | `{"ok": true, "model": "groq/openai/gpt-oss-120b"}` |

Set `VAKYA_APP_KEY` to require an `X-Vakya-Key` header.

## Tests and eval

```bash
.venv/Scripts/python -m pytest              # offline, no API calls
.venv/Scripts/python eval/run_eval.py       # 30 live cases against your provider
.venv/Scripts/python eval/run_eval.py client chain   # only matching case ids
```

The eval checks each case automatically (3 distinct options, intent, language mix, no AI phrasing, memory use) and prints every reply so you can read them. Results go to `eval/results/`. On a free tier it runs 2 cases at a time and waits out per-minute limits; it stops at once if the daily cap is hit. A full run takes several minutes and uses about 60k tokens, so on Groq's free tier run it at most twice a day, or pass case ids to run only some.
