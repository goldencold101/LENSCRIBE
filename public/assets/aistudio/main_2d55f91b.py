"""Lenscribe - local-first image-to-text transcription.

Text is recognized with the Windows OCR engine that ships with Windows, with a
local Ollama vision model when one is installed, or - only when explicitly
chosen - with Google Gemini using a key supplied by the user. No key is ever
created or provided by Lenscribe itself.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import hmac
import io
import json
import logging
import math
import os
import re
import secrets
import shutil
import subprocess
import tempfile
import time
from collections import deque
from contextlib import AsyncExitStack, asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import AsyncIterator, Optional
from urllib.parse import quote
from xml.sax.saxutils import escape as xml_escape

import httpx
from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import (
    HTMLResponse,
    JSONResponse,
    PlainTextResponse,
    StreamingResponse,
)
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from PIL import Image, ImageOps, UnidentifiedImageError
from starlette.datastructures import MutableHeaders, UploadFile
from starlette.exceptions import HTTPException as StarletteHTTPException

from docx_export import build_docx
from image_card import CARD_ASPECTS, CARD_THEMES, build_text_image
from pdf_export import build_pdf
from plain_export import build_csv, build_txt
from pptx_export import build_pptx
from waitlist import WaitlistStore, normalize_email
from xlsx_export import build_xlsx

logger = logging.getLogger("lenscribe")

BASE_DIR = Path(__file__).resolve().parent

# Ollama publishes the Qwen 2.5-VL family as "qwen2.5vl" (no hyphen).
DEFAULT_MODEL = "qwen2.5vl:7b"
DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

SYSTEM_PROMPT = (
    "You are a precise OCR and document transcription engine. Transcribe the visible "
    "text in the image exactly as written, without summarizing, correcting, or inventing "
    "content. Preserve reading order, line breaks, lists, and tables. Return Markdown only."
)

DEFAULT_PROMPT = (
    "Transcribe all text in this image. Preserve the original layout and formatting, "
    "including headings, lists, and tables, using Markdown."
)

STREAM_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}

DOCX_MEDIA_TYPE = (
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)
XLSX_MEDIA_TYPE = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)
PPTX_MEDIA_TYPE = (
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
)

EXPORT_FORMATS = {
    "docx": (DOCX_MEDIA_TYPE, build_docx),
    "pdf": ("application/pdf", build_pdf),
    "xlsx": (XLSX_MEDIA_TYPE, build_xlsx),
    "pptx": (PPTX_MEDIA_TYPE, build_pptx),
    "csv": ("text/csv; charset=utf-8", build_csv),
    "txt": ("text/plain; charset=utf-8", build_txt),
}

TEXT_IMAGE_MAX_CHARS = 12_000

# Prices are placeholders for the paid tiers; edit freely before launch.
PLANS = (
    {
        "id": "free",
        "name": "Free",
        "price": "$0",
        "period": "forever",
        "tagline": "Everything you need to digitize documents today.",
        "features": [
            "Windows OCR on the recognition server",
            "Multiple-image upload, camera scan, crop and rotate",
            "Word, PDF, Excel, PowerPoint, CSV and TXT export",
            "Shareable text-to-image cards",
            "No account, no tracking",
        ],
        "cta": "Open the app",
        "available": True,
        "featured": False,
    },
    {
        "id": "pro",
        "name": "Pro",
        "price": "$9",
        "period": "per month",
        "tagline": "For people who scan every day.",
        "features": [
            "Everything in Free",
            "High-accuracy cloud vision for handwriting and dense tables",
            "Higher-volume document processing",
            "Priority processing",
            "Email support",
        ],
        "cta": "Join the Pro waitlist",
        "available": False,
        "featured": True,
    },
    {
        "id": "plus",
        "name": "Plus",
        "price": "$5",
        "period": "per month",
        "tagline": "Professional daily use with generous processing.",
        "features": [
            "Everything in Free",
            "Everything in Pro",
            "Unlimited scans per day",
            "Higher priority processing",
            "Early access to new features",
        ],
        "cta": "Upgrade to Plus",
        "available": False,
        "featured": False,
    },
    {
        "id": "team",
        "name": "Team",
        "price": "$29",
        "period": "per month - 5 seats",
        "tagline": "For offices that digitize paperwork together.",
        "features": [
            "Everything in Pro",
            "Shared seats under one bill",
            "Usage reports for admins",
            "Invoice billing",
            "Onboarding call",
        ],
        "cta": "Join the Team waitlist",
        "available": False,
        "featured": False,
    },
)
WAITLIST_PLANS = {plan["id"] for plan in PLANS if not plan["available"]}

# --------------------------------------------------------------------------- #
# Plan resolution (Free, Plus, Pro) - allows per-request selection for testing
# --------------------------------------------------------------------------- #

# Normal users default to "free" unless specified otherwise.
# Useful for shared environments, testing, and free-tier usage.
def _resolve_plan(request: Request) -> str:
    """Determine the plan from request headers/query, fallback to free."""
    # Try X-Plan header first (case-insensitive)
    plan = request.headers.get("X-Plan", "").strip().lower()
    if plan:
        plan = plan.split(",")[0].strip()
        if plan in {p["id"] for p in PLANS}:
            return plan
    # Then query param - e.g., ?plan=plus
    query = request.query_params.get("plan", "")
    if query:
        query = query.strip().lower()
        if query in {p["id"] for p in PLANS}:
            return query
    # Fallback to free
    return "free"

# Per-plan limits. Adjust before launch.
PLAN_LIMITS = {
    "free": {"requests_per_hour": 20, "image_bytes": 5 * 1024 * 1024, "export_formats": ["txt", "png"]},
    "plus": {"requests_per_hour": 200, "image_bytes": 50 * 1024 * 1024, "export_formats": ["docx", "pdf", "xlsx", "pptx", "csv", "txt", "png"]},
    "pro": {"requests_per_hour": 500, "image_bytes": 200 * 1024 * 1024, "export_formats": ["docx", "pdf", "xlsx", "pptx", "csv", "txt", "png"]},
    "team": {"requests_per_hour": 1000, "image_bytes": 500 * 1024 * 1024, "export_formats": ["docx", "pdf", "xlsx", "pptx", "csv", "txt", "png"]},
}

ENGINES = {"auto", "ollama", "windows", "gemini"}

_DATA_URI_PREFIX = re.compile(r"^data:image/[a-zA-Z0-9.+-]+;base64,", re.IGNORECASE)


# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #


def _load_dotenv(path: Path = BASE_DIR / ".env") -> None:
    """Load KEY=VALUE pairs from a local .env without overriding real env vars."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return

    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or key in os.environ:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        os.environ[key] = value


@dataclass(frozen=True, slots=True)
class Settings:
    """Runtime configuration, populated from environment variables by from_env().

    Every field has a safe default so the app runs out of the box with a local
    Ollama instance; production overrides are read from the environment (or a
    .env file loaded by _load_dotenv()).
    """

    ollama_base_url: str = "http://127.0.0.1:11434"
    model: str = DEFAULT_MODEL
    request_timeout: float = 180.0
    connect_timeout: float = 5.0
    # Hard cap on uploaded image payloads (bytes) before decoding.
    max_upload_bytes: int = 12 * 1024 * 1024
    max_prompt_chars: int = 2000
    # Images are downscaled so no side exceeds this before being sent to a model.
    max_image_side: int = 2400
    rate_limit_requests: int = 30
    rate_limit_window: float = 60.0
    temperature: float = 0.0
    num_ctx: int = 8192
    ocr_engine: str = "auto"
    enable_windows_ocr: bool = True
    windows_ocr_timeout: float = 45.0
    gemini_api_key: str = ""
    gemini_model: str = DEFAULT_GEMINI_MODEL
    gemini_timeout: float = 120.0
    brand_name: str = "Lenscribe"
    contact_email: str = ""
    public_base_url: str = ""
    admin_token: str = ""
    # Empty keeps the waitlist in memory (tests); from_env points it at data/.
    data_dir: str = ""
    waitlist_rate_limit: int = 5

    @classmethod
    def from_env(cls) -> "Settings":
        _load_dotenv()

        def env_int(name: str, default: int) -> int:
            try:
                return int(os.getenv(name, default))
            except (TypeError, ValueError):
                return default

        def env_float(name: str, default: float) -> float:
            try:
                return float(os.getenv(name, default))
            except (TypeError, ValueError):
                return default

        return cls(
            ollama_base_url=os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/"),
            model=os.getenv("OLLAMA_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL,
            request_timeout=env_float("OLLAMA_TIMEOUT", 180.0),
            connect_timeout=env_float("OLLAMA_CONNECT_TIMEOUT", 5.0),
            max_upload_bytes=max(1024, env_int("MAX_UPLOAD_BYTES", 12 * 1024 * 1024)),
            max_prompt_chars=max(100, env_int("MAX_PROMPT_CHARS", 2000)),
            max_image_side=max(256, env_int("MAX_IMAGE_SIDE", 2400)),
            rate_limit_requests=max(0, env_int("RATE_LIMIT_REQUESTS", 30)),
            rate_limit_window=max(1.0, env_float("RATE_LIMIT_WINDOW", 60.0)),
            temperature=env_float("MODEL_TEMPERATURE", 0.0),
            num_ctx=max(2048, env_int("MODEL_NUM_CTX", 8192)),
            ocr_engine=(
                os.getenv("LENS_OCR_ENGINE", "auto").strip().lower()
                if os.getenv("LENS_OCR_ENGINE", "auto").strip().lower() in ENGINES
                else "auto"
            ),
            enable_windows_ocr=os.getenv("LENS_ENABLE_WINDOWS_OCR", "1").strip().lower()
            not in {"0", "false", "no", "off"},
            windows_ocr_timeout=max(5.0, env_float("LENS_WINDOWS_OCR_TIMEOUT", 45.0)),
            gemini_api_key=os.getenv("GEMINI_API_KEY", "").strip(),
            gemini_model=os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL).strip()
            or DEFAULT_GEMINI_MODEL,
            gemini_timeout=max(15.0, env_float("GEMINI_TIMEOUT", 120.0)),
            brand_name=os.getenv("BRAND_NAME", "Lenscribe").strip() or "Lenscribe",
            contact_email=os.getenv("CONTACT_EMAIL", "").strip(),
            public_base_url=os.getenv("PUBLIC_BASE_URL", "").strip().rstrip("/"),
            admin_token=os.getenv("ADMIN_TOKEN", "").strip(),
            data_dir=os.getenv("DATA_DIR", str(BASE_DIR / "data")).strip(),
            waitlist_rate_limit=max(0, env_int("WAITLIST_RATE_LIMIT", 5)),
        )


# --------------------------------------------------------------------------- #
# Errors
# --------------------------------------------------------------------------- #


class ApiError(Exception):
    """App-level error that renders as a structured JSON error body.

    Carries a machine-readable `code` plus optional `hint` and `retry_after`
    so clients (and the UI) can react without string-matching messages.
    """

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        *,
        hint: Optional[str] = None,
        retry_after: Optional[float] = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.hint = hint
        self.retry_after = retry_after

    def payload(self) -> dict:
        error = {"code": self.code, "message": self.message}
        if self.hint:
            error["hint"] = self.hint
        return {"success": False, "error": error}

    @property
    def headers(self) -> dict[str, str]:
        if self.retry_after is None:
            return {}
        return {"Retry-After": str(max(1, math.ceil(self.retry_after)))}


# --------------------------------------------------------------------------- #
# Rate limiting
# --------------------------------------------------------------------------- #


class RateLimiter:
    """Sliding-window limiter keyed by client host.

    Each key keeps a deque of recent hit timestamps; hits older than the
    window are pruned. If the deque is full the next hit returns the number of
    seconds until the oldest entry expires, which the caller turns into a 429
    with a Retry-After header. The deque is capped by pruning stale buckets
    once the map grows past 2048 keys.
    """

    def __init__(self, limit: int, window: float) -> None:
        self._limit = limit
        self._window = window
        self._hits: dict[str, deque[float]] = {}
        self._lock = asyncio.Lock()

    async def hit(self, key: str) -> Optional[float]:
        if self._limit <= 0:
            return None

        now = time.monotonic()
        cutoff = now - self._window
        async with self._lock:
            bucket = self._hits.setdefault(key, deque())
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()

            if len(bucket) >= self._limit:
                return max(0.001, bucket[0] + self._window - now)

            bucket.append(now)
            if len(self._hits) > 2048:
                for stale in [
                    k for k, v in self._hits.items() if not v or v[-1] <= cutoff
                ]:
                    del self._hits[stale]
            return None


async def _guard(
    request: Request, limiter: RateLimiter, action: str = "transcriptions"
) -> None:
    """Raise ApiError(429) when the caller has exceeded `limiter`.

    Keys the limiter by client host so each device gets its own budget.
    """
    host = request.client.host if request.client else "anonymous"
    retry_after = await limiter.hit(host)
    if retry_after is not None:
        raise ApiError(
            429,
            "rate_limited",
            f"Too many {action} from this device. Retry in {math.ceil(retry_after)}s.",
            retry_after=retry_after,
        )


# --------------------------------------------------------------------------- #
# Input parsing and image normalization
# --------------------------------------------------------------------------- #


def _clean_prompt(prompt: Optional[str], settings: Settings) -> str:
    if prompt is None:
        return DEFAULT_PROMPT
    if not isinstance(prompt, str):
        raise ApiError(400, "invalid_prompt", "The prompt must be a string.")
    prompt = prompt.strip()
    if not prompt:
        return DEFAULT_PROMPT
    if len(prompt) > settings.max_prompt_chars:
        raise ApiError(
            400,
            "prompt_too_long",
            f"The prompt must be {settings.max_prompt_chars} characters or fewer.",
        )
    return prompt


def _clean_engine(value: object, default: str = "auto") -> str:
    if value is None:
        return default
    if not isinstance(value, str):
        raise ApiError(400, "invalid_engine", "The engine must be a string.")
    engine = value.strip().lower() or default
    if engine not in ENGINES:
        raise ApiError(
            400,
            "invalid_engine",
            "The engine must be one of: auto, windows, ollama, gemini.",
        )
    return engine


async def _parse_request(request: Request, settings: Settings) -> tuple[bytes, str, str]:
    content_type = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    declared_length = request.headers.get("content-length")
    if declared_length:
        try:
            if int(declared_length) > settings.max_upload_bytes * 2 + 1_000_000:
                raise ApiError(413, "file_too_large", _too_large_message(settings))
        except ValueError:
            raise ApiError(400, "invalid_request", "Content-Length header is not a number.")

    if content_type == "application/json":
        raw = await request.body()
        if len(raw) > settings.max_upload_bytes * 2 + 1_000_000:
            raise ApiError(413, "file_too_large", _too_large_message(settings))
        try:
            body = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise ApiError(400, "invalid_json", "The request body is not valid JSON.")
        if not isinstance(body, dict):
            raise ApiError(400, "invalid_request", "The JSON body must be an object.")

        encoded = body.get("image_base64") or body.get("image")
        if not isinstance(encoded, str) or not encoded.strip():
            raise ApiError(400, "missing_image", "Provide 'image_base64' alongside the prompt.")
        if len(encoded) > (settings.max_upload_bytes * 4 // 3) + 64:
            raise ApiError(413, "file_too_large", _too_large_message(settings))

        encoded = _DATA_URI_PREFIX.sub("", encoded.strip())
        encoded = re.sub(r"\s+", "", encoded)
        try:
            data = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError):
            raise ApiError(
                400, "invalid_image_data", "The image is not valid Base64 image data."
            )
        return (
            data,
            _clean_prompt(body.get("prompt"), settings),
            _clean_engine(body.get("engine")),
        )

    if content_type == "multipart/form-data":
        try:
            form = await request.form()
        except Exception as exc:  # malformed multipart bodies raise inside Starlette
            logger.debug("Malformed multipart form: %s", exc)
            raise ApiError(400, "invalid_form", "The upload could not be parsed.")

        uploaded = form.get("image") or form.get("file")
        if uploaded is None:
            raise ApiError(400, "missing_image", "Attach an image in the 'image' field.")
        if not isinstance(uploaded, UploadFile):
            raise ApiError(400, "invalid_image", "The 'image' field must be a file upload.")
        if uploaded.size is not None and uploaded.size > settings.max_upload_bytes:
            raise ApiError(413, "file_too_large", _too_large_message(settings))

        try:
            data = await uploaded.read(settings.max_upload_bytes + 1)
        finally:
            await uploaded.close()
        if len(data) > settings.max_upload_bytes:
            raise ApiError(413, "file_too_large", _too_large_message(settings))

        raw_prompt = form.get("prompt")
        prompt = raw_prompt if isinstance(raw_prompt, str) else None
        raw_engine = form.get("engine")
        engine = raw_engine if isinstance(raw_engine, str) else None
        return data, _clean_prompt(prompt, settings), _clean_engine(engine)

    raise ApiError(
        415,
        "unsupported_content_type",
        "Send multipart/form-data with an image file, or JSON with 'image_base64'.",
    )


def _too_large_message(settings: Settings) -> str:
    megabytes = settings.max_upload_bytes / (1024 * 1024)
    return f"Images must be {megabytes:g} MB or smaller."


def _normalize_image(data: bytes, settings: Settings) -> tuple[str, int, int]:
    """Validate the bytes as an image, cap its size, and return Base64 JPEG."""
    if not data:
        raise ApiError(400, "empty_image", "The uploaded file is empty.")
    if len(data) > settings.max_upload_bytes:
        raise ApiError(413, "file_too_large", _too_large_message(settings))

    try:
        with Image.open(io.BytesIO(data)) as probe:
            probe.verify()

        with Image.open(io.BytesIO(data)) as source:
            image = ImageOps.exif_transpose(source)
            if image.mode in ("RGBA", "LA", "P"):
                image = image.convert("RGBA")
                flattened = Image.new("RGB", image.size, (255, 255, 255))
                flattened.paste(image, mask=image.getchannel("A"))
                image = flattened
            elif image.mode != "RGB":
                image = image.convert("RGB")

            if max(image.size) > settings.max_image_side:
                image.thumbnail(
                    (settings.max_image_side, settings.max_image_side),
                    Image.Resampling.LANCZOS,
                )

            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=88, optimize=True, progressive=True)
            width, height = image.size
    except ApiError:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError, Image.DecompressionBombError):
        raise ApiError(
            415,
            "unsupported_media_type",
            "That file is not a readable image. Upload a PNG, JPEG, WebP, GIF, BMP, or TIFF.",
        )

    return base64.b64encode(buffer.getvalue()).decode("ascii"), width, height


# --------------------------------------------------------------------------- #
# Ollama client
# --------------------------------------------------------------------------- #


def _stats(payload: dict) -> dict:
    stats: dict[str, float] = {}
    for key in ("eval_count", "prompt_eval_count", "eval_duration", "total_duration"):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            stats[key] = value
    eval_count = stats.get("eval_count")
    eval_duration = stats.get("eval_duration")
    if eval_count and eval_duration:
        stats["tokens_per_second"] = round(eval_count / (eval_duration / 1e9), 1)
    return stats


def _retry_after_from(headers: httpx.Headers) -> Optional[float]:
    try:
        return max(0.0, float(headers.get("retry-after", "")))
    except (TypeError, ValueError):
        return None


class OllamaClient:
    def __init__(self, http: httpx.AsyncClient, settings: Settings) -> None:
        self._http = http
        self._settings = settings
        self._probe_cache: Optional[dict] = None
        self._probe_at = 0.0

    async def probe(self) -> dict:
        now = time.monotonic()
        if self._probe_cache is not None and now - self._probe_at < 5.0:
            return self._probe_cache

        started = time.perf_counter()
        result: dict = {
            "online": False,
            "models": [],
            "model_present": False,
            "latency_ms": 0,
        }
        try:
            response = await self._http.get(
                "/api/tags", timeout=httpx.Timeout(2.0, connect=0.6)
            )
            if response.status_code == 200:
                payload = response.json()
                models = [
                    str(entry.get("name", ""))
                    for entry in payload.get("models") or []
                    if isinstance(entry, dict)
                ]
                result.update(
                    online=True,
                    models=models,
                    model_present=_model_present(self._settings.model, models),
                )
        except (httpx.RequestError, ValueError):
            pass
        result["latency_ms"] = round((time.perf_counter() - started) * 1000)

        self._probe_cache = result
        self._probe_at = now
        return result

    def _payload(self, image_b64: str, prompt: str, *, stream: bool) -> dict:
        return {
            "model": self._settings.model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": prompt, "images": [image_b64]},
            ],
            "stream": stream,
            "options": {
                "temperature": self._settings.temperature,
                "num_ctx": self._settings.num_ctx,
            },
        }

    def _upstream_error(self, response: httpx.Response) -> ApiError:
        status = response.status_code
        body = (response.text or "")[:400]
        logger.warning("Ollama upstream returned %s: %s", status, body)
        model = self._settings.model

        if status == 404:
            return ApiError(
                503,
                "model_unavailable",
                f"Ollama is running but the model '{model}' is not installed.",
                hint=f"ollama pull {model}",
            )
        if status == 429:
            return ApiError(
                429,
                "upstream_rate_limited",
                "The local model server is rate limiting requests. Try again shortly.",
                retry_after=_retry_after_from(response.headers),
            )
        if status >= 500:
            return ApiError(
                503,
                "model_unavailable",
                "Ollama failed while running the model.",
                hint="Check the 'ollama serve' logs, then retry.",
            )
        return ApiError(
            502,
            "upstream_rejected",
            f"Ollama rejected the transcription request (HTTP {status}).",
        )

    @property
    def _timeout(self) -> httpx.Timeout:
        return httpx.Timeout(
            self._settings.request_timeout,
            connect=self._settings.connect_timeout,
            write=30.0,
            pool=5.0,
        )

    async def transcribe(self, image_b64: str, prompt: str) -> dict:
        payload = self._payload(image_b64, prompt, stream=False)
        try:
            response = await self._http.post(
                "/api/chat", json=payload, timeout=self._timeout
            )
        except httpx.TimeoutException as exc:
            raise ApiError(
                504, "timeout", "The local model took too long to respond."
            ) from exc
        except httpx.RequestError as exc:
            raise self._unreachable() from exc

        if response.status_code != 200:
            raise self._upstream_error(response)
        try:
            data = response.json()
        except (json.JSONDecodeError, ValueError):
            raise ApiError(
                502, "invalid_upstream_response", "Ollama returned an unreadable response."
            )

        text = (data.get("message") or {}).get("content") or ""
        return {"text": text, "stats": _stats(data)}

    async def stream(self, image_b64: str, prompt: str) -> AsyncIterator[tuple[str, dict]]:
        """Yield (event_name, payload) tuples from Ollama's NDJSON stream."""
        payload = self._payload(image_b64, prompt, stream=True)
        emitted = False
        try:
            async with self._http.stream(
                "POST", "/api/chat", json=payload, timeout=self._timeout
            ) as response:
                if response.status_code != 200:
                    await response.aread()
                    raise self._upstream_error(response)

                yield ("meta", {"model": self._settings.model, "provider": "ollama"})
                emitted = True

                final_stats: dict = {}
                async for line in response.aiter_lines():
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        chunk = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    piece = (chunk.get("message") or {}).get("content") or ""
                    if piece:
                        yield ("delta", {"text": piece})
                    if chunk.get("done"):
                        final_stats = _stats(chunk)
                        break

                yield ("done", {"stats": final_stats})
        except ApiError as exc:
            if not emitted:
                raise
            yield ("error", {"error": exc.payload()["error"]})
        except httpx.TimeoutException:
            error = ApiError(504, "timeout", "The local model stopped responding mid-stream.")
            if not emitted:
                raise error
            yield ("error", {"error": error.payload()["error"]})
        except httpx.RequestError as exc:
            error = self._unreachable()
            if not emitted:
                raise error from exc
            yield ("error", {"error": error.payload()["error"]})

    def _unreachable(self) -> ApiError:
        return ApiError(
            503,
            "ollama_unreachable",
            f"Could not reach Ollama at {self._settings.ollama_base_url}.",
            hint="Start it with 'ollama serve', then retry.",
        )


# --------------------------------------------------------------------------- #
# Gemini engine (cloud, explicit opt-in)
# --------------------------------------------------------------------------- #


def _gemini_label(model: str) -> str:
    known = {
        "gemini-3.5-flash": "Gemini 3.5 Flash",
        "gemini-3.8-flash": "Gemini 3.8 Flash",
        "gemini-2.5-flash": "Gemini 2.5 Flash",
        "gemini-2.5-pro": "Gemini 2.5 Pro",
        "gemini-2.0-flash": "Gemini 2.0 Flash",
    }
    if model in known:
        return known[model]
    if model.startswith("gemini-"):
        return "Gemini " + model[len("gemini-") :].replace("-", " ").title()
    return f"Gemini {model}"


def _gemini_text(payload: dict) -> str:
    pieces: list[str] = []
    for candidate in payload.get("candidates") or []:
        if not isinstance(candidate, dict):
            continue
        content = candidate.get("content") or {}
        for part in content.get("parts") or []:
            if isinstance(part, dict) and isinstance(part.get("text"), str):
                pieces.append(part["text"])
    return "".join(pieces)


def _gemini_stats(payload: dict) -> dict:
    usage = payload.get("usageMetadata") or {}
    stats: dict[str, int] = {}
    mapping = {
        "promptTokenCount": "prompt_eval_count",
        "candidatesTokenCount": "eval_count",
        "totalTokenCount": "total_tokens",
    }
    for source, target in mapping.items():
        value = usage.get(source)
        if isinstance(value, int):
            stats[target] = value
    return stats


def _gemini_block_reason(payload: dict) -> Optional[str]:
    feedback = payload.get("promptFeedback") or {}
    reason = feedback.get("blockReason")
    if isinstance(reason, str) and reason:
        return reason
    for candidate in payload.get("candidates") or []:
        if isinstance(candidate, dict) and candidate.get("finishReason") in {
            "SAFETY",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
        }:
            return str(candidate["finishReason"])
    return None


def _gemini_key(request: Request, settings: Settings) -> str:
    header = request.headers.get("x-gemini-key")
    key = header.strip() if isinstance(header, str) else ""
    if len(key) > 256:
        raise ApiError(400, "gemini_key_invalid", "The Gemini API key is too long.")
    # A server-side key wins so normal deployments never depend on the browser.
    return settings.gemini_api_key or key


def _gemini_key_required() -> ApiError:
    return ApiError(
        400,
        "gemini_key_required",
        "Gemini needs an API key before it can transcribe.",
        hint=(
            "Create a free key at https://aistudio.google.com/apikey, then paste it "
            "into the Gemini prompt. Lenscribe never creates a key for you."
        ),
    )


class GeminiClient:
    """Calls Google's Generative Language API with a user-supplied key."""

    def __init__(self, http: httpx.AsyncClient, settings: Settings) -> None:
        self._http = http
        self._settings = settings

    def info(self) -> dict:
        return {
            "configured": bool(self._settings.gemini_api_key),
            "model": self._settings.gemini_model,
            "label": _gemini_label(self._settings.gemini_model),
            "key_url": GEMINI_KEY_URL,
        }

    @property
    def _timeout(self) -> httpx.Timeout:
        return httpx.Timeout(
            self._settings.gemini_timeout,
            connect=8.0,
            write=30.0,
            pool=5.0,
        )

    def _url(self, *, stream: bool) -> str:
        action = "streamGenerateContent" if stream else "generateContent"
        query = "?alt=sse" if stream else ""
        return f"/models/{quote(self._settings.gemini_model, safe='')}:{action}{query}"

    def _payload(self, image_b64: str, prompt: str) -> dict:
        return {
            "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {
                            "inline_data": {
                                "mime_type": "image/jpeg",
                                "data": image_b64,
                            }
                        },
                        {"text": prompt},
                    ],
                }
            ],
            "generationConfig": {"temperature": self._settings.temperature},
        }

    def _upstream_error(self, response: httpx.Response) -> ApiError:
        status = response.status_code
        body = (response.text or "")[:400]
        logger.warning("Gemini upstream returned %s", status)

        if status in (401, 403) or "API_KEY" in body or "API key" in body:
            return ApiError(
                401,
                "gemini_key_invalid",
                "Gemini rejected the API key.",
                hint=(
                    "Create a new free key at https://aistudio.google.com/apikey, "
                    "then paste it again."
                ),
            )
        if status == 404:
            return ApiError(
                503,
                "model_unavailable",
                f"The Gemini model '{self._settings.gemini_model}' is not available.",
                hint="Set GEMINI_MODEL to a model your key can use, then retry.",
            )
        if status == 429:
            return ApiError(
                429,
                "upstream_rate_limited",
                "Gemini is rate limiting this key. Wait a moment, then retry.",
                retry_after=_retry_after_from(response.headers),
            )
        if status >= 500:
            return ApiError(
                503,
                "gemini_unavailable",
                "Gemini is temporarily unavailable. Try again shortly.",
            )
        return ApiError(
            502,
            "upstream_rejected",
            f"Gemini rejected the transcription request (HTTP {status}).",
        )

    def _unreachable(self) -> ApiError:
        return ApiError(
            503,
            "gemini_unreachable",
            "Could not reach Gemini.",
            hint="Check this device's internet connection, then retry.",
        )

    async def transcribe(self, image_b64: str, prompt: str, key: str) -> dict:
        if not key:
            raise _gemini_key_required()
        try:
            response = await self._http.post(
                self._url(stream=False),
                json=self._payload(image_b64, prompt),
                headers={"x-goog-api-key": key},
                timeout=self._timeout,
            )
        except httpx.TimeoutException as exc:
            raise ApiError(
                504, "timeout", "Gemini took too long to respond."
            ) from exc
        except httpx.RequestError as exc:
            raise self._unreachable() from exc

        if response.status_code != 200:
            raise self._upstream_error(response)
        try:
            payload = response.json()
        except (json.JSONDecodeError, ValueError):
            raise ApiError(
                502, "invalid_upstream_response", "Gemini returned an unreadable response."
            )

        text = _gemini_text(payload)
        if not text.strip():
            reason = _gemini_block_reason(payload)
            if reason:
                raise ApiError(
                    502,
                    "gemini_blocked",
                    f"Gemini refused this image ({reason}).",
                    hint="Try a different image or a different engine.",
                )
        return {"text": text, "stats": _gemini_stats(payload)}

    async def stream(
        self, image_b64: str, prompt: str, key: str
    ) -> AsyncIterator[tuple[str, dict]]:
        if not key:
            raise _gemini_key_required()

        emitted = False
        received = False
        blocked_reason: Optional[str] = None
        final_stats: dict = {}
        try:
            async with self._http.stream(
                "POST",
                self._url(stream=True),
                json=self._payload(image_b64, prompt),
                headers={"x-goog-api-key": key, "Accept": "text/event-stream"},
                timeout=self._timeout,
            ) as response:
                if response.status_code != 200:
                    await response.aread()
                    raise self._upstream_error(response)

                yield (
                    "meta",
                    {
                        "model": _gemini_label(self._settings.gemini_model),
                        "provider": "gemini",
                        "engine": "gemini",
                    },
                )
                emitted = True

                async for line in response.aiter_lines():
                    line = line.strip()
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if not data or data == "[DONE]":
                        continue
                    try:
                        chunk = json.loads(data)
                    except json.JSONDecodeError:
                        continue

                    reason = _gemini_block_reason(chunk)
                    if reason:
                        blocked_reason = reason
                    piece = _gemini_text(chunk)
                    if piece:
                        received = True
                        yield ("delta", {"text": piece})
                    stats = _gemini_stats(chunk)
                    if stats:
                        final_stats = stats

                if not received and blocked_reason:
                    error = ApiError(
                        502,
                        "gemini_blocked",
                        f"Gemini refused this image ({blocked_reason}).",
                        hint="Try a different image or a different engine.",
                    )
                    yield ("error", {"error": error.payload()["error"]})
                    return

                yield ("done", {"stats": final_stats})
        except ApiError as exc:
            if not emitted:
                raise
            yield ("error", {"error": exc.payload()["error"]})
        except httpx.TimeoutException:
            error = ApiError(504, "timeout", "Gemini stopped responding mid-stream.")
            if not emitted:
                raise error
            yield ("error", {"error": error.payload()["error"]})
        except httpx.RequestError as exc:
            error = self._unreachable()
            if not emitted:
                raise error from exc
            yield ("error", {"error": error.payload()["error"]})


# --------------------------------------------------------------------------- #
# Windows OCR engine
# --------------------------------------------------------------------------- #


class WindowsOcr:
    """Runs the OCR engine bundled with Windows through PowerShell."""

    label = "Windows OCR"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._script = BASE_DIR / "windows_ocr.ps1"
        self._cache: Optional[dict] = None
        self._cache_at = 0.0
        self._lock = asyncio.Lock()

    @staticmethod
    def _flags() -> int:
        return getattr(subprocess, "CREATE_NO_WINDOW", 0)

    async def info(self, *, refresh: bool = False) -> dict:
        if os.name != "nt" or not self._settings.enable_windows_ocr:
            return {
                "available": False,
                "language": None,
                "languages": [],
                "reason": "Windows OCR is only available when Lenscribe runs on Windows.",
            }
        if (
            self._cache is not None
            and not refresh
            and time.monotonic() - self._cache_at < 300.0
        ):
            return self._cache

        async with self._lock:
            if (
                self._cache is not None
                and not refresh
                and time.monotonic() - self._cache_at < 300.0
            ):
                return self._cache
            info = await asyncio.to_thread(self._probe)
            self._cache = info
            self._cache_at = time.monotonic()
            return info

    def _probe(self) -> dict:
        powershell = shutil.which("powershell.exe") or shutil.which("powershell")
        if not powershell:
            return {
                "available": False,
                "language": None,
                "languages": [],
                "reason": "Windows PowerShell was not found.",
            }

        command = (
            "[Windows.Media.Ocr.OcrEngine, Windows.Foundation, "
            "ContentType=WindowsRuntime] | Out-Null; "
            "[Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | "
            "ForEach-Object { $_.LanguageTag }"
        )
        try:
            completed = subprocess.run(
                [
                    powershell,
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    command,
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=15,
                creationflags=self._flags(),
            )
        except (OSError, subprocess.SubprocessError):
            return {
                "available": False,
                "language": None,
                "languages": [],
                "reason": "Windows OCR could not be queried.",
            }

        languages = [
            line.strip().lstrip("\ufeff")
            for line in (completed.stdout or "").splitlines()
            if line.strip()
        ]
        available = completed.returncode == 0 and bool(languages)
        return {
            "available": available,
            "language": languages[0] if languages else None,
            "languages": languages,
            "reason": (
                None
                if available
                else "No Windows OCR language is installed."
            ),
        }

    async def recognize(self, image_bytes: bytes) -> dict:
        info = await self.info()
        if not info.get("available"):
            raise _windows_unavailable(info)

        started = time.perf_counter()
        try:
            result = await asyncio.to_thread(
                self._run_ocr, image_bytes, info.get("language")
            )
        except subprocess.TimeoutExpired as exc:
            raise ApiError(
                504,
                "windows_ocr_timeout",
                "Windows OCR took too long to read this image.",
                hint="Try a smaller crop, then retry.",
            ) from exc
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
            logger.warning("Windows OCR failed: %s", exc)
            raise ApiError(
                503,
                "windows_ocr_failed",
                "Windows OCR could not read this image.",
                hint="Try another image, or switch to the Ollama engine.",
            ) from exc

        result["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        return result

    def _run_ocr(self, image_bytes: bytes, language: Optional[str]) -> dict:
        powershell = shutil.which("powershell.exe") or shutil.which("powershell")
        if not powershell or not self._script.exists():
            raise RuntimeError("The Windows OCR helper is unavailable.")

        with tempfile.TemporaryDirectory(prefix="lenscribe-ocr-") as folder:
            image_path = Path(folder) / "page.jpg"
            output_path = Path(folder) / "result.json"
            image_path.write_bytes(image_bytes)

            command = [
                powershell,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(self._script),
                "-Path",
                str(image_path),
                "-OutPath",
                str(output_path),
            ]
            if language:
                command.extend(["-Language", language])

            completed = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=self._settings.windows_ocr_timeout,
                creationflags=self._flags(),
            )

            if not output_path.exists():
                detail = (completed.stderr or completed.stdout or "").strip()
                raise RuntimeError(detail[:400] or "Windows OCR produced no output.")

            payload = json.loads(output_path.read_text(encoding="utf-8"))

        if not payload.get("ok"):
            raise RuntimeError(payload.get("error") or "Windows OCR failed.")

        return {
            "text": str(payload.get("text") or ""),
            "lines": payload.get("lines") or [],
            "language": payload.get("language"),
            "width": payload.get("width"),
            "height": payload.get("height"),
        }


def _windows_unavailable(info: dict) -> ApiError:
    return ApiError(
        503,
        "windows_ocr_unavailable",
        info.get("reason") or "Windows OCR is not available on this machine.",
        hint=(
            "Open Windows Settings > Time & language > Language & region, "
            "add a language with OCR support, then retry."
        ),
    )


async def _pick_engine(
    requested: str,
    settings: Settings,
    ollama: OllamaClient,
    windows: WindowsOcr,
) -> str:
    preference = requested if requested != "auto" else settings.ocr_engine
    if preference == "windows":
        info = await windows.info()
        if not info.get("available"):
            raise _windows_unavailable(info)
        return "windows"
    if preference == "ollama":
        return "ollama"
    if preference == "gemini":
        return "gemini"

    windows_info = await windows.info()
    if windows_info.get("available"):
        return "windows"

    ollama_info = await ollama.probe()
    if ollama_info["online"] and ollama_info["model_present"]:
        return "ollama"
    return "ollama"


# --------------------------------------------------------------------------- #
# SSE helpers
# --------------------------------------------------------------------------- #


def _sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode("utf-8")


async def _sse_events(
    ollama: OllamaClient,
    windows: WindowsOcr,
    gemini: GeminiClient,
    engine: str,
    image_b64: str,
    prompt: str,
    gemini_key: str,
    started: float,
) -> AsyncIterator[bytes]:
    if engine == "windows":
        result = await windows.recognize(base64.b64decode(image_b64))
        yield _sse(
            "meta",
            {"model": "Windows OCR", "provider": "windows", "engine": "windows"},
        )
        if result["text"]:
            yield _sse("delta", {"text": result["text"]})
        yield _sse(
            "done",
            {
                "stats": {
                    "lines": len(result.get("lines") or []),
                    "language": result.get("language"),
                },
                "elapsed_ms": round((time.perf_counter() - started) * 1000),
            },
        )
        return

    if engine == "gemini":
        async for event, payload in gemini.stream(image_b64, prompt, gemini_key):
            if event == "done":
                payload = {
                    **payload,
                    "engine": "gemini",
                    "elapsed_ms": round((time.perf_counter() - started) * 1000),
                }
            yield _sse(event, payload)
        return

    async for event, payload in ollama.stream(image_b64, prompt):
        if event == "done":
            payload = {
                **payload,
                "engine": "ollama",
                "elapsed_ms": round((time.perf_counter() - started) * 1000),
            }
        yield _sse(event, payload)


async def _chain(first: bytes, rest: AsyncIterator[bytes]) -> AsyncIterator[bytes]:
    yield first
    async for chunk in rest:
        yield chunk


def _model_present(model: str, names: list[str]) -> bool:
    base = model.split(":")[0]
    for name in names:
        if name == model:
            return True
        if ":" not in model and name.split(":")[0] == base:
            return True
    return False


def _export_filename(title: Optional[str], extension: str) -> str:
    base = re.sub(r"[^\w\-. ]+", "", title or "lenscribe-transcription")
    base = re.sub(r"\s+", "-", base).strip("-. ") or "lenscribe-transcription"
    return f"{base[:60]}.{extension}"


def _content_disposition(filename: str, extension: str) -> str:
    ascii_name = re.sub(r"[^A-Za-z0-9._-]+", "-", filename).strip("-")
    if not ascii_name.lower().endswith("." + extension):
        ascii_name = f"lenscribe.{extension}"
    return (
        f'attachment; filename="{ascii_name}"; '
        f"filename*=UTF-8''{quote(filename)}"
    )


async def _parse_export_request(request: Request) -> tuple[str, Optional[str]]:
    content_type = (
        (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    )
    if content_type != "application/json":
        raise ApiError(
            415,
            "unsupported_media_type",
            "Send JSON with 'markdown' and an optional 'title'.",
        )

    raw = await request.body()
    if len(raw) > 4_000_000:
        raise ApiError(
            413, "document_too_large", "The transcription is too large to export."
        )
    try:
        body = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise ApiError(400, "invalid_json", "The request body is not valid JSON.")
    if not isinstance(body, dict):
        raise ApiError(400, "invalid_request", "The JSON body must be an object.")

    markdown = body.get("markdown")
    if not isinstance(markdown, str) or not markdown.strip():
        raise ApiError(400, "empty_document", "There is no text to export.")
    if len(markdown) > 2_000_000:
        raise ApiError(
            413, "document_too_large", "The transcription is too large to export."
        )

    title = body.get("title")
    title = (
        title.strip()[:120] if isinstance(title, str) and title.strip() else None
    )
    return markdown, title


async def _parse_card_request(
    request: Request,
) -> tuple[str, Optional[str], str, str]:
    content_type = (
        (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    )
    if content_type != "application/json":
        raise ApiError(
            415,
            "unsupported_media_type",
            "Send JSON with 'text', 'theme', and 'aspect'.",
        )

    raw = await request.body()
    if len(raw) > 2_000_000:
        raise ApiError(
            413, "document_too_large", "The text is too large to render as an image."
        )
    try:
        body = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise ApiError(400, "invalid_json", "The request body is not valid JSON.")
    if not isinstance(body, dict):
        raise ApiError(400, "invalid_request", "The JSON body must be an object.")

    text = body.get("text")
    if not isinstance(text, str) or not text.strip():
        raise ApiError(
            400, "empty_card_text", "Add some text before rendering an image card."
        )
    if len(text) > TEXT_IMAGE_MAX_CHARS:
        raise ApiError(
            413,
            "card_text_too_long",
            f"Keep the card text under {TEXT_IMAGE_MAX_CHARS:,} characters.",
        )

    theme = body.get("theme", "emerald")
    theme = theme.strip().lower() if isinstance(theme, str) else ""
    if theme not in CARD_THEMES:
        raise ApiError(
            400,
            "unsupported_theme",
            f"'{theme}' is not a card theme. Choose one of: "
            + ", ".join(CARD_THEMES)
            + ".",
        )

    aspect = body.get("aspect", "square")
    aspect = aspect.strip().lower() if isinstance(aspect, str) else ""
    if aspect not in CARD_ASPECTS:
        raise ApiError(
            400,
            "unsupported_aspect",
            f"'{aspect}' is not a card size. Choose one of: "
            + ", ".join(CARD_ASPECTS)
            + ".",
        )

    title = body.get("title")
    title = (
        title.strip()[:120] if isinstance(title, str) and title.strip() else None
    )
    return text, title, theme, aspect


async def _parse_waitlist_request(request: Request) -> tuple[Optional[str], str, bool]:
    content_type = request.headers.get("content-type", "").lower()
    if not content_type.startswith("application/json"):
        raise ApiError(
            415, "unsupported_media_type", "Send the waitlist form as application/json."
        )
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 4096:
            raise ApiError(413, "payload_too_large", "The waitlist request is too large.")
    try:
        payload = json.loads(body or b"{}")
    except (ValueError, UnicodeDecodeError):
        raise ApiError(400, "invalid_json", "The request body is not valid JSON.")
    if not isinstance(payload, dict):
        raise ApiError(400, "invalid_json", "The request body must be a JSON object.")

    # Hidden "website" field: humans leave it empty, form-filling bots don't.
    is_bot = bool(str(payload.get("website") or "").strip())
    email = normalize_email(payload.get("email"))
    if email is None and not is_bot:
        raise ApiError(
            400, "invalid_email", "Enter a valid email address, like you@example.com."
        )
    plan = str(payload.get("plan") or "pro").strip().lower()
    if plan not in WAITLIST_PLANS:
        raise ApiError(
            400,
            "unsupported_plan",
            "Choose one of: " + ", ".join(sorted(WAITLIST_PLANS)) + ".",
        )
    return email, plan, is_bot


# --------------------------------------------------------------------------- #
# Site: security headers and brand assets
# --------------------------------------------------------------------------- #

FAVICON_SVG = (
    "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
    "<rect width='32' height='32' rx='8' fill='#05070b'/>"
    "<path d='M9 12V9h3M23 12V9h-3M9 20v3h3M23 20v3h-3' stroke='#34d399' "
    "stroke-width='2' stroke-linecap='round' fill='none'/>"
    "<path d='M13 13h6M13 16h6M13 19h3' stroke='#22d3ee' stroke-width='1.6' "
    "stroke-linecap='round'/></svg>"
)

OG_IMAGE_TEXT = (
    "## Image to editable text, in seconds\n\n"
    "- Crop any region of a photo, scan or screenshot\n"
    "- Windows OCR by default; optional cloud recognition\n"
    "- Export to Word, PDF, Excel, PowerPoint, CSV or TXT\n\n"
    "> Images are processed in memory and never stored."
)

_DOCS_PATHS = ("/docs", "/redoc", "/openapi.json")


def _content_security_policy(nonce: str) -> str:
    return "; ".join(
        (
            "default-src 'self'",
            f"script-src 'self' 'nonce-{nonce}' https://cdn.jsdelivr.net",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com "
            "https://cdn.jsdelivr.net",
            "font-src 'self' https://fonts.gstatic.com data:",
            "img-src 'self' data: blob:",
            "media-src 'self' blob:",
            # Cropper.js reads EXIF orientation by fetching blob:/data: image URLs.
            "connect-src 'self' blob: data:",
            "worker-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
        )
    )


class SecurityHeadersMiddleware:
    """Pure ASGI middleware so SSE streams pass through untouched."""

    def __init__(self, app, hsts: bool = False) -> None:
        self.app = app
        self.hsts = hsts

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        nonce = secrets.token_urlsafe(18)
        scope.setdefault("state", {})["csp_nonce"] = nonce
        path = scope.get("path", "")

        async def send_with_headers(message) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                headers.setdefault("X-Content-Type-Options", "nosniff")
                headers.setdefault("X-Frame-Options", "DENY")
                headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
                headers.setdefault(
                    "Permissions-Policy",
                    "camera=(self), microphone=(), geolocation=(), payment=()",
                )
                headers.setdefault("Cross-Origin-Opener-Policy", "same-origin")
                if not path.startswith(_DOCS_PATHS):
                    headers.setdefault(
                        "Content-Security-Policy", _content_security_policy(nonce)
                    )
                if path.startswith("/api/"):
                    headers.setdefault("Cache-Control", "no-store")
                if self.hsts:
                    headers.setdefault(
                        "Strict-Transport-Security", "max-age=31536000; includeSubDomains"
                    )
            await send(message)

        await self.app(scope, receive, send_with_headers)


# --------------------------------------------------------------------------- #
# App factory
# --------------------------------------------------------------------------- #


def create_app(
    settings: Optional[Settings] = None,
    transport: Optional[httpx.AsyncBaseTransport] = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    http = httpx.AsyncClient(
        base_url=settings.ollama_base_url,
        transport=transport,
        # Ollama is local-only; ignore proxy env vars (some contain IPv6
        # NO_PROXY entries that httpx cannot parse).
        trust_env=False,
        timeout=httpx.Timeout(settings.request_timeout, connect=settings.connect_timeout),
        limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
    )
    gemini_http = httpx.AsyncClient(
        base_url=GEMINI_BASE_URL,
        transport=transport,
        trust_env=False,
        timeout=httpx.Timeout(settings.gemini_timeout, connect=8.0),
        limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
    )
    ollama = OllamaClient(http, settings)
    windows = WindowsOcr(settings)
    gemini = GeminiClient(gemini_http, settings)
    limiter = RateLimiter(settings.rate_limit_requests, settings.rate_limit_window)
    waitlist_limiter = RateLimiter(settings.waitlist_rate_limit, 600.0)
    # One shared limiter per subscription plan so rate limits persist across
    # requests instead of resetting on every call.
    plan_limiters = {
        plan_id: RateLimiter(
            limits.get("requests_per_hour", settings.rate_limit_requests),
            settings.rate_limit_window,
        )
        for plan_id, limits in PLAN_LIMITS.items()
    }
    waitlist = WaitlistStore(
        Path(settings.data_dir) / "lenscribe.db" if settings.data_dir else None
    )
    templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
    og_image: dict[str, bytes] = {}

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        async with AsyncExitStack() as stack:
            stack.push_async_callback(http.aclose)
            stack.push_async_callback(gemini_http.aclose)
            stack.callback(waitlist.close)
            yield

    app = FastAPI(
        title="Lenscribe",
        description=(
            "Local-first image-to-text transcription with Windows OCR, an optional "
            "local Ollama vision model, or Gemini with your own API key."
        ),
        version="1.3.0",
        lifespan=lifespan,
    )
    app.add_middleware(
        SecurityHeadersMiddleware,
        hsts=settings.public_base_url.startswith("https://"),
    )
    app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")
    app.state.settings = settings
    app.state.ollama = ollama
    app.state.windows_ocr = windows
    app.state.gemini = gemini
    app.state.http = http
    app.state.gemini_http = gemini_http
    app.state.limiter = limiter
    app.state.waitlist = waitlist

    def base_url(request: Request) -> str:
        return settings.public_base_url or str(request.base_url).rstrip("/")

    def page(
        request: Request, name: str, status_code: int = 200, **extra
    ) -> HTMLResponse:
        context = {
            "brand": settings.brand_name,
            "contact_email": settings.contact_email,
            "base_url": base_url(request),
            "year": datetime.now(timezone.utc).year,
            "plans": PLANS,
            **extra,
        }
        return templates.TemplateResponse(
            request=request, name=name, context=context, status_code=status_code
        )

    @app.exception_handler(ApiError)
    async def handle_api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code, content=exc.payload(), headers=exc.headers
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                "success": False,
                "error": {
                    "code": "invalid_request",
                    "message": "The request did not pass validation.",
                    "fields": exc.errors(),
                },
            },
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_error(request: Request, exc: StarletteHTTPException) -> Response:
        if (
            exc.status_code == 404
            and not request.url.path.startswith("/api/")
            and "text/html" in request.headers.get("accept", "")
        ):
            return page(request, "notfound.html", status_code=404)
        code = {404: "not_found", 405: "method_not_allowed"}.get(
            exc.status_code, f"http_{exc.status_code}"
        )
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "success": False,
                "error": {"code": code, "message": str(exc.detail)},
            },
        )

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("Unhandled error on %s", request.url.path, exc_info=exc)
        return JSONResponse(
            status_code=500,
            content={
                "success": False,
                "error": {
                    "code": "internal_error",
                    "message": "An unexpected error occurred. Check the server logs.",
                },
            },
        )

    @app.get("/", response_class=HTMLResponse)
    async def index(request: Request) -> HTMLResponse:
        return page(request, "index.html")

    @app.get("/privacy", response_class=HTMLResponse)
    async def privacy(request: Request) -> HTMLResponse:
        return page(request, "legal.html", doc="privacy", title="Privacy Policy")

    @app.get("/terms", response_class=HTMLResponse)
    async def terms(request: Request) -> HTMLResponse:
        return page(request, "legal.html", doc="terms", title="Terms of Service")

    @app.get("/app", response_class=HTMLResponse)
    async def workspace(request: Request) -> HTMLResponse:
        plan = _resolve_plan(request)
        config = {
            "model": settings.model,
            "maxUploadMb": round(settings.max_upload_bytes / (1024 * 1024), 1),
            "maxPromptChars": settings.max_prompt_chars,
            "offline": True,
            "engine": settings.ocr_engine,
            "geminiModel": settings.gemini_model,
            "cardMaxChars": TEXT_IMAGE_MAX_CHARS,
            "plan": plan,
            "planLimits": PLAN_LIMITS.get(plan, PLAN_LIMITS["free"]),
        }
        return templates.TemplateResponse(
            request=request, name="app.html", context={"config": config}
        )

    @app.get("/api/health")
    async def health() -> dict:
        started = time.perf_counter()
        ollama_info = await ollama.probe()
        windows_info = await windows.info()
        gemini_info = gemini.info()

        if settings.ocr_engine == "windows":
            engine = "windows" if windows_info["available"] else None
        elif settings.ocr_engine == "ollama":
            engine = "ollama" if ollama_info["online"] else None
        elif settings.ocr_engine == "gemini":
            engine = "gemini" if gemini_info["configured"] else None
        elif windows_info["available"]:
            engine = "windows"
        elif ollama_info["online"] and ollama_info["model_present"]:
            engine = "ollama"
        else:
            engine = None

        return {
            "status": "online" if ollama_info["online"] else "offline",
            "offline": True,
            "provider": "ollama",
            "engine": engine,
            "model": settings.model,
            "model_present": ollama_info["model_present"],
            "installed_models": ollama_info["models"],
            "latency_ms": round((time.perf_counter() - started) * 1000),
            "engines": {
                "ollama": {
                    "available": bool(
                        ollama_info["online"] and ollama_info["model_present"]
                    ),
                    "online": bool(ollama_info["online"]),
                    "model": settings.model,
                    "model_present": bool(ollama_info["model_present"]),
                    "installed_models": ollama_info["models"],
                },
                "windows": {
                    "available": bool(windows_info["available"]),
                    "language": windows_info.get("language"),
                    "label": "Windows OCR",
                },
                "gemini": {
                    "configured": bool(gemini_info["configured"]),
                    "model": gemini_info["model"],
                    "label": gemini_info["label"],
                    "key_url": gemini_info["key_url"],
                },
            },
        }

    @app.post("/api/transcribe")
    async def transcribe(request: Request) -> dict:
        # Resolve the caller's plan (Free, Plus, or Pro) and apply its limits.
        plan = _resolve_plan(request)
        plan_limiter = plan_limiters.get(plan, limiter)
        await _guard(request, plan_limiter)
        data, prompt, requested_engine = await _parse_request(request, settings)
        # Enforce per-plan image size limit.
        max_bytes = PLAN_LIMITS[plan].get("image_bytes", settings.max_upload_bytes)
        if len(data) > max_bytes:
            raise ApiError(
                status_code=413,
                code="image_too_large",
                message=f"Image exceeds the {plan} plan limit of {max_bytes // (1024 * 1024)} MB.",
            )
        image_b64, width, height = await asyncio.to_thread(
            _normalize_image, data, settings
        )
        engine = await _pick_engine(requested_engine, settings, ollama, windows)

        started = time.perf_counter()
        if engine == "windows":
            result = await windows.recognize(base64.b64decode(image_b64))
            return {
                "success": True,
                "text": result["text"],
                "prompt": prompt,
                "provider": "windows",
                "engine": "windows",
                "model": "Windows OCR",
                "elapsed_ms": round((time.perf_counter() - started) * 1000),
                "image": {"width": width, "height": height, "bytes": len(data)},
                "stats": {
                    "lines": len(result.get("lines") or []),
                    "language": result.get("language"),
                },
            }

        if engine == "gemini":
            key = _gemini_key(request, settings)
            result = await gemini.transcribe(image_b64, prompt, key)
            return {
                "success": True,
                "text": result["text"],
                "prompt": prompt,
                "provider": "gemini",
                "engine": "gemini",
                "model": _gemini_label(settings.gemini_model),
                "elapsed_ms": round((time.perf_counter() - started) * 1000),
                "image": {"width": width, "height": height, "bytes": len(data)},
                "stats": result["stats"],
            }

        result = await ollama.transcribe(image_b64, prompt)
        return {
            "success": True,
            "text": result["text"],
            "prompt": prompt,
            "provider": "ollama",
            "engine": "ollama",
            "model": settings.model,
            "elapsed_ms": round((time.perf_counter() - started) * 1000),
            "image": {"width": width, "height": height, "bytes": len(data)},
            "stats": result["stats"],
        }

    @app.post("/api/transcribe/stream")
    async def transcribe_stream(request: Request) -> StreamingResponse:
        await _guard(request, limiter)
        data, prompt, requested_engine = await _parse_request(request, settings)
        image_b64, _, _ = await asyncio.to_thread(_normalize_image, data, settings)
        engine = await _pick_engine(requested_engine, settings, ollama, windows)
        started = time.perf_counter()

        gemini_key = _gemini_key(request, settings) if engine == "gemini" else ""
        events = _sse_events(
            ollama, windows, gemini, engine, image_b64, prompt, gemini_key, started
        )
        try:
            # Pull the first event now so connection/model errors become real
            # HTTP status codes instead of a 200 SSE stream that immediately fails.
            first = await events.__anext__()
        except StopAsyncIteration:
            raise ApiError(502, "empty_stream", "The model closed the stream unexpectedly.")

        return StreamingResponse(
            _chain(first, events),
            media_type="text/event-stream",
            headers=STREAM_HEADERS,
        )

    @app.post("/api/export/{fmt}")
    async def export_document(fmt: str, request: Request) -> Response:
        await _guard(request, limiter)
        extension = fmt.strip().lower()
        spec = EXPORT_FORMATS.get(extension)
        if spec is None:
            raise ApiError(
                404,
                "unsupported_format",
                f"'{fmt}' is not an export format. Choose one of: "
                + ", ".join(sorted(EXPORT_FORMATS))
                + ".",
            )

        markdown, title = await _parse_export_request(request)
        media_type, builder = spec
        payload = await asyncio.to_thread(builder, markdown, title)
        filename = _export_filename(title, extension)
        return Response(
            content=payload,
            media_type=media_type,
            headers={
                "Content-Disposition": _content_disposition(filename, extension),
                "Content-Length": str(len(payload)),
            },
        )

    @app.post("/api/text-image")
    async def text_image(request: Request) -> Response:
        await _guard(request, limiter)
        text, title, theme, aspect = await _parse_card_request(request)
        payload = await asyncio.to_thread(
            build_text_image, text, title, theme, aspect
        )
        filename = _export_filename(title, "png")
        return Response(
            content=payload,
            media_type="image/png",
            headers={
                "Content-Disposition": _content_disposition(filename, "png"),
                "Content-Length": str(len(payload)),
                "Cache-Control": "no-store",
                "X-Card-Aspect": aspect,
                "X-Card-Theme": theme,
            },
        )

    @app.post("/api/waitlist")
    async def join_waitlist(request: Request) -> dict:
        await _guard(request, waitlist_limiter, "sign-ups")
        email, plan, is_bot = await _parse_waitlist_request(request)
        if not is_bot and email is not None:
            await asyncio.to_thread(waitlist.add, email, plan)
        # Same response for new and existing addresses, so the list can't be probed.
        return {
            "success": True,
            "plan": plan,
            "message": "You're on the list. We'll email you once when it opens.",
        }

    @app.get("/api/admin/waitlist.csv")
    async def export_waitlist(request: Request) -> Response:
        if not settings.admin_token:
            raise StarletteHTTPException(404, "Not Found")
        supplied = request.headers.get("authorization", "")
        expected = f"Bearer {settings.admin_token}"
        if not hmac.compare_digest(supplied.encode(), expected.encode()):
            raise ApiError(401, "unauthorized", "A valid admin token is required.")
        body = await asyncio.to_thread(waitlist.export_csv)
        return Response(
            content=("\ufeff" + body).encode("utf-8"),
            media_type="text/csv; charset=utf-8",
            headers={
                "Content-Disposition": 'attachment; filename="waitlist.csv"',
                "Cache-Control": "no-store",
            },
        )

    @app.get("/healthz")
    async def healthz() -> dict:
        return {"status": "ok", "version": app.version}

    @app.get("/favicon.svg")
    async def favicon() -> Response:
        return Response(
            content=FAVICON_SVG,
            media_type="image/svg+xml",
            headers={"Cache-Control": "public, max-age=86400"},
        )

    @app.get("/og.png")
    async def og_png() -> Response:
        if "png" not in og_image:
            og_image["png"] = await asyncio.to_thread(
                build_text_image,
                OG_IMAGE_TEXT,
                f"{settings.brand_name} - image to text",
                "emerald",
                "wide",
            )
        return Response(
            content=og_image["png"],
            media_type="image/png",
            headers={"Cache-Control": "public, max-age=86400"},
        )

    @app.get("/robots.txt")
    async def robots(request: Request) -> PlainTextResponse:
        return PlainTextResponse(
            "User-agent: *\nAllow: /\nDisallow: /api/\n\n"
            f"Sitemap: {base_url(request)}/sitemap.xml\n"
        )

    @app.get("/sitemap.xml")
    async def sitemap(request: Request) -> Response:
        root = base_url(request)
        urls = "".join(
            f"<url><loc>{xml_escape(root + path)}</loc></url>"
            for path in ("/", "/app", "/privacy", "/terms")
        )
        return Response(
            content=(
                '<?xml version="1.0" encoding="UTF-8"?>'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                f"{urls}</urlset>"
            ),
            media_type="application/xml",
        )

    @app.get("/manifest.webmanifest")
    async def manifest() -> JSONResponse:
        return JSONResponse(
            {
                "name": f"{settings.brand_name} - Image to Text",
                "short_name": settings.brand_name,
                "start_url": "/app",
                "scope": "/",
                "display": "standalone",
                "background_color": "#05070b",
                "theme_color": "#05070b",
                "icons": [
                    {"src": "/favicon.svg", "sizes": "any", "type": "image/svg+xml"}
                ],
            },
            media_type="application/manifest+json",
        )

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=os.getenv("HOST", "127.0.0.1"),
        port=int(os.getenv("PORT", "8000")),
        reload=os.getenv("RELOAD", "0") == "1",
    )
