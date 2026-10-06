import asyncio
import hmac
import logging
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import config, llm, phone_link, providers, usage
from .errors import DailyLimitReached
from .prompts import clean_draft
from .schemas import ComposeRequest, ComposeResponse, StyleCard, StyleCardRequest, SuggestRequest, SuggestResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("vakya")

_STATIC_DIR = Path(__file__).resolve().parent.parent / "static"

@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(phone_link.keep_linked(config.PORT)) if config.PHONE_LINK else None
    yield
    if task:
        task.cancel()


# In production the interactive API docs (/docs, /redoc, /openapi.json) are hidden:
# no need to show strangers how to call the server.
_docs = {} if config.SHOW_DOCS else {"docs_url": None, "redoc_url": None, "openapi_url": None}
app = FastAPI(title="Vakya", version="0.4.1", lifespan=lifespan, **_docs)


def require_app_key(x_vakya_key: str | None = Header(default=None)) -> None:
    if config.APP_KEY is None:
        if config.PRODUCTION:
            # Never run an open AI endpoint on the internet because a setting was forgotten.
            raise HTTPException(status_code=503, detail="server is missing VAKYA_APP_KEY")
        return
    if x_vakya_key is None or not hmac.compare_digest(x_vakya_key, config.APP_KEY):
        raise HTTPException(status_code=401, detail="bad or missing X-Vakya-Key")


def _error(status: int):
    async def handler(_: Request, exc: Exception):
        return JSONResponse(status_code=status, content={"detail": str(exc)})

    return handler


# Most specific class wins, so these override the generic LLMError -> 502.
app.add_exception_handler(llm.MissingCredentials, _error(503))
app.add_exception_handler(DailyLimitReached, _error(429))
app.add_exception_handler(llm.LLMRefusal, _error(422))
app.add_exception_handler(llm.RateLimited, _error(429))
app.add_exception_handler(llm.ProviderTimeout, _error(504))
app.add_exception_handler(llm.ProviderUnavailable, _error(503))
app.add_exception_handler(llm.LLMError, _error(502))


@app.get("/health")
async def health():
    return {"ok": True, "version": app.version, "model": providers.describe()}


@contextmanager
def _ai_access(request: Request, groq_key: str | None, device: str | None):
    """Use the phone's own Groq key if it sent one (no limit); otherwise count this
    request against the phone's daily share of the shared key."""
    token = None
    if groq_key and groq_key.strip():
        token = providers.use_for_request(providers.user_groq_provider(groq_key.strip()))
    elif config.DAILY_LIMIT:
        who = device or (request.client.host if request.client else "unknown")
        if not usage.take(who, config.DAILY_LIMIT):
            raise DailyLimitReached(
                f"You've used today's {config.DAILY_LIMIT} free AI replies. Add your own free Groq key "
                "in Vakya for unlimited replies, or try again tomorrow."
            )
    try:
        yield
    finally:
        if token is not None:
            providers.reset_request(token)


@app.post("/suggest", response_model=SuggestResponse, dependencies=[Depends(require_app_key)])
async def suggest(
    req: SuggestRequest,
    request: Request,
    x_groq_key: str | None = Header(default=None),
    x_vakya_device: str | None = Header(default=None),
):
    # Shape of the request only, never its text, to debug what the phone read.
    mine = sum(m.sender == "me" for m in req.messages)
    media = [m.media for m in req.messages if m.media]
    images = sum(1 for m in req.messages if m.image)
    log.info(
        "suggest app=%s messages=%d (mine=%d) media=%s images=%d rel=%s style=%s draft_len=%d "
        "style_card=%s examples=%d memory=%d",
        req.app, len(req.messages), mine, media, images, req.relationship, req.style,
        len(clean_draft(req.draft)), req.style_card is not None, len(req.examples), len(req.memory),
    )
    with _ai_access(request, x_groq_key, x_vakya_device):
        return await llm.suggest_replies(req)


@app.post("/compose", response_model=ComposeResponse, dependencies=[Depends(require_app_key)])
async def compose(
    req: ComposeRequest,
    request: Request,
    x_groq_key: str | None = Header(default=None),
    x_vakya_device: str | None = Header(default=None),
):
    """Write it for me: what I want to say (one rough sentence) -> the message in each style."""
    log.info(
        "compose app=%s messages=%d intent_len=%d rel=%s style_card=%s examples=%d",
        req.app, len(req.messages), len(req.intent), req.relationship, req.style_card is not None, len(req.examples),
    )
    with _ai_access(request, x_groq_key, x_vakya_device):
        return await llm.compose_message(req)


@app.post("/style-card", response_model=StyleCard, dependencies=[Depends(require_app_key)])
async def style_card(
    req: StyleCardRequest,
    request: Request,
    x_groq_key: str | None = Header(default=None),
    x_vakya_device: str | None = Header(default=None),
):
    with _ai_access(request, x_groq_key, x_vakya_device):
        return await llm.build_style_card(req.my_messages)


@app.get("/")
async def landing_page():
    """Serve the Vakya landing page."""
    index = _STATIC_DIR / "index.html"
    if index.exists():
        return FileResponse(index, media_type="text/html")
    return JSONResponse({"message": "Vakya API", "docs": "/docs"})


# Mount static files last so API routes take priority
if _STATIC_DIR.exists():
    app.mount("/static", StaticFiles(directory=str(_STATIC_DIR)), name="static")
