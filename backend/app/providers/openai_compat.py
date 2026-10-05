"""Any OpenAI-compatible chat API: Groq, Gemini, Ollama, OpenRouter, Cerebras, ..."""

import asyncio
import copy
import json
import logging
import re
import time

import openai
from pydantic import BaseModel, ValidationError

from ..errors import LLMError, LLMRefusal, MissingCredentials, ProviderTimeout, ProviderUnavailable, RateLimited

log = logging.getLogger("vakya.llm")

_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$")

_BAD_OUTPUT_CODES = {"json_validate_failed", "output_parse_failed", "tool_use_failed"}

# Longest we wait for a per-minute limit to clear before giving up (the app waits 30 s).
MAX_WAIT_S = 12


def _seconds(duration: str) -> float:
    """'1h2m3.5s' -> 3723.5, '690ms' -> 0.69"""
    units = {"h": 3600, "m": 60, "s": 1, "ms": 0.001}
    return sum(float(n) * units[u] for n, u in re.findall(r"([\d.]+)(ms|h|m|s)", duration))


def _rate_limited(provider: str, e: "openai.RateLimitError") -> RateLimited:
    """Groq says which limit was hit, e.g. '... tokens per day (TPD) ... try again in 11m22.9s'."""
    body = e.body if isinstance(e.body, dict) else {}
    message = str((body.get("error", body) or {}).get("message", "")) if isinstance(body, dict) else ""
    daily = "per day" in message.lower()
    wait = re.search(r"try again in ((?:\d+h)?(?:\d+m(?!s))?(?:\d+(?:\.\d+)?m?s)?)", message)
    when = ", try again in " + re.sub(r"[.]\d+s$", "s", wait.group(1)) if wait else ""
    kind = "daily" if daily else "per-minute"
    return RateLimited(
        f"{provider} free {kind} limit reached{when}",
        daily=daily,
        retry_after=_seconds(wait.group(1)) if wait else None,
    )


def _error_code(e: "openai.APIStatusError") -> str:
    body = e.body if isinstance(e.body, dict) else {}
    err = body.get("error", body)
    return str(err.get("code") or "") if isinstance(err, dict) else ""


def strict_schema(model: type[BaseModel]) -> dict:
    """JSON Schema in the shape strict mode wants: refs inlined, no titles,
    every property required, no extra properties."""
    schema = model.model_json_schema()
    defs = schema.pop("$defs", {})

    def walk(node):
        if isinstance(node, dict):
            if "$ref" in node:
                return walk(copy.deepcopy(defs[node["$ref"].split("/")[-1]]))
            node = {
                # Under "properties" the keys are field names, so keep them all.
                k: {name: walk(sub) for name, sub in v.items()} if k == "properties" else walk(v)
                for k, v in node.items()
                if k not in ("title", "default")
            }
            if node.get("type") == "object":
                node["additionalProperties"] = False
                node["required"] = list(node.get("properties", {}))
            return node
        if isinstance(node, list):
            return [walk(v) for v in node]
        return node

    return walk(schema)


class OpenAICompatProvider:
    def __init__(
        self,
        name: str,
        base_url: str,
        api_key: str,
        model: str,
        json_mode: str = "schema",
        max_tokens_param: str = "max_tokens",
        extra: dict | None = None,
        timeout: float = 15,
        client=None,
        fallback_models: list[str] | None = None,
        vision_model: str | None = None,
        model_extra: dict[str, dict] | None = None,
    ):
        # Per-model request options that override `extra` (models differ in what they accept).
        self.model_extra = model_extra or {}
        self.max_wait_s = MAX_WAIT_S
        self.name = name
        self.model = model
        self.vision_model = vision_model
        # Tried in order when the one before is rate-limited. On Groq every model
        # has its own free allowance, so a fallback adds capacity, not just retries.
        self.models = [model, *(fallback_models or [])]
        self._cooldown_until: dict[str, float] = {}
        self.json_mode = json_mode
        self.max_tokens_param = max_tokens_param
        self.extra = extra or {}
        # No SDK retries: on a free tier a 429 retry waits out the per-minute window
        # (10-30 s), and a late suggestion is worse than a quick "try again".
        self.client = client or openai.AsyncOpenAI(
            base_url=base_url, api_key=api_key, timeout=timeout, max_retries=0
        )

    async def parse(self, system: str, user_content: str, output_format: type[BaseModel], max_tokens: int):
        schema = strict_schema(output_format)
        if self.json_mode == "schema":
            response_format = {
                "type": "json_schema",
                "json_schema": {"name": output_format.__name__, "strict": True, "schema": schema},
            }
        else:
            # Plain JSON mode: the model only knows the shape from the prompt.
            response_format = {"type": "json_object"}
            system = f"{system}\nReply with only a JSON object that follows this JSON Schema:\n{json.dumps(schema)}"

        messages = [{"role": "system", "content": system}, {"role": "user", "content": user_content}]
        started = time.perf_counter()
        limited: RateLimited | None = None
        for _round in range(2):
            for model in self.models:
                if self._cooldown_until.get(model, 0) > time.monotonic():
                    continue
                try:
                    return await self._call(model, messages, response_format, output_format, max_tokens, started)
                except RateLimited as e:
                    # Skip this model until the provider says it has room again.
                    self._cooldown_until[model] = time.monotonic() + (e.retry_after or 60)
                    log.info("rate limited provider=%s model=%s daily=%s", self.name, model, e.daily)
                    limited = e
            # Every model is busy. If one frees up within a few seconds (a per-minute
            # limit), waiting beats showing an error; daily limits are hours away.
            wait = min(self._cooldown_until.get(m, 0) for m in self.models) - time.monotonic()
            if wait > self.max_wait_s:
                break
            await asyncio.sleep(max(wait, 0) + 0.3)
        raise limited or RateLimited(f"{self.name} free limit reached on all models, try again soon")

    async def describe_image(self, image_b64: str, prompt: str) -> str:
        """One short description of a photo or sticker, from the provider's vision model."""
        if not self.vision_model:
            raise LLMError(f"{self.name} has no vision model configured")
        try:
            resp = await self.client.chat.completions.create(
                model=self.vision_model,
                messages=[{"role": "user", "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
                ]}],
                **{self.max_tokens_param: 300},
            )
        except openai.RateLimitError as e:
            raise _rate_limited(self.name, e) from e
        except openai.APIError as e:
            raise LLMError(f"{self.name} vision error") from e
        log.info("vision provider=%s model=%s in=%s out=%s", self.name, self.vision_model,
                 getattr(resp.usage, "prompt_tokens", "?"), getattr(resp.usage, "completion_tokens", "?"))
        return resp.choices[0].message.content or ""

    async def _call(self, model, messages, response_format, output_format, max_tokens, started):
        last_error = ""
        for _attempt in range(2):
            try:
                resp = await self.client.chat.completions.create(
                    model=model,
                    messages=messages,
                    response_format=response_format,
                    **{self.max_tokens_param: max_tokens},
                    **{**self.extra, **self.model_extra.get(model, {})},
                )
            except openai.RateLimitError as e:
                raise _rate_limited(self.name, e) from e
            except openai.AuthenticationError as e:
                raise MissingCredentials(f"{self.name} rejected the API key") from e
            except openai.PermissionDeniedError as e:
                raise ProviderUnavailable(f"{self.name} refused access to this key/project") from e
            except (openai.APITimeoutError, openai.APIConnectionError) as e:
                raise ProviderTimeout(f"{self.name} didn't answer in time") from e
            except openai.InternalServerError as e:
                # "Model overloaded / high demand": temporary, so treat it like a short
                # rate limit and let the next model or provider answer.
                raise RateLimited(f"{self.name} is busy right now, try again in a minute", retry_after=30) from e
            except openai.APIStatusError as e:
                code = _error_code(e)
                if e.status_code == 400 and code in _BAD_OUTPUT_CODES:
                    # The provider rejected the model's own output (e.g. Groq's
                    # json_validate_failed); a fresh attempt usually succeeds.
                    last_error = code
                    continue
                raise LLMError(f"{self.name} error {e.status_code} {code}".rstrip()) from e

            choice = resp.choices[0]
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            usage = resp.usage
            # Log sizes and timings only, never message content.
            log.info(
                "%s provider=%s model=%s ms=%d in=%s out=%s finish=%s",
                output_format.__name__, self.name, model, elapsed_ms,
                getattr(usage, "prompt_tokens", "?"), getattr(usage, "completion_tokens", "?"),
                choice.finish_reason,
            )
            if choice.finish_reason == "content_filter":
                raise LLMRefusal("model declined to answer")
            if choice.finish_reason == "length":
                raise LLMError("model output was cut off")

            text = _FENCE.sub("", (choice.message.content or "").strip())
            try:
                return output_format.model_validate_json(text), elapsed_ms
            except ValidationError as e:
                # Small free models sometimes slip; one retry usually fixes it.
                last_error = f"{e.error_count()} schema errors"
        raise LLMError(f"model output did not match schema: {last_error}")
