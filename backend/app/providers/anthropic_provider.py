"""Claude via the Anthropic SDK (paid)."""

import logging
import time

import anthropic
from pydantic import BaseModel, ValidationError

from ..errors import LLMError, LLMRefusal, MissingCredentials, ProviderTimeout, RateLimited

log = logging.getLogger("vakya.llm")


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, model: str, timeout: float = 15):
        self.model = model
        # Credentials come from ANTHROPIC_API_KEY (or another SDK-supported source).
        self.client = anthropic.AsyncAnthropic(timeout=timeout, max_retries=1)
        if self.client.api_key is None and self.client.auth_token is None and self.client.credentials is None:
            raise MissingCredentials("no Anthropic credentials: set ANTHROPIC_API_KEY")

    async def describe_image(self, image_b64: str, prompt: str) -> str:
        try:
            response = await self.client.messages.create(
                model=self.model,
                max_tokens=200,
                messages=[{"role": "user", "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": image_b64}},
                    {"type": "text", "text": prompt},
                ]}],
            )
        except anthropic.RateLimitError as e:
            raise RateLimited("anthropic rate limit") from e
        except anthropic.APIError as e:
            raise LLMError("anthropic vision error") from e
        return next((b.text for b in response.content if b.type == "text"), "")

    async def parse(self, system: str, user_content: str, output_format: type[BaseModel], max_tokens: int):
        started = time.perf_counter()
        try:
            response = await self.client.messages.parse(
                model=self.model,
                max_tokens=max_tokens,
                # Stable instructions first so they can be cached. Haiku 4.5 only
                # caches prefixes of 4096+ tokens, so this is a no-op until the prompt grows.
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": user_content}],
                output_format=output_format,
            )
        except ValidationError as e:
            raise LLMError(f"model output did not match schema: {e.error_count()} errors") from e
        except anthropic.RateLimitError as e:
            raise RateLimited("anthropic rate limit") from e
        except anthropic.AuthenticationError as e:
            raise MissingCredentials("anthropic rejected the API key") from e
        except (anthropic.APITimeoutError, anthropic.APIConnectionError) as e:
            raise ProviderTimeout("anthropic didn't answer in time") from e
        except anthropic.APIStatusError as e:
            raise LLMError(f"anthropic error {e.status_code}") from e

        elapsed_ms = int((time.perf_counter() - started) * 1000)
        usage = response.usage
        # Log sizes and timings only, never message content.
        log.info(
            "%s provider=anthropic model=%s ms=%d in=%d out=%d cache_read=%s stop=%s req=%s",
            output_format.__name__, response.model, elapsed_ms, usage.input_tokens,
            usage.output_tokens, usage.cache_read_input_tokens, response.stop_reason,
            response._request_id,
        )
        if response.stop_reason == "refusal":
            raise LLMRefusal("model declined to answer")
        if response.stop_reason == "max_tokens":
            raise LLMError("model output was cut off")
        if response.parsed_output is None:
            raise LLMError("model returned no parsable output")
        return response.parsed_output, elapsed_ms
