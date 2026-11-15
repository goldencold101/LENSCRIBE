"""Endpoint tests for Lenscribe.

Ollama is mocked at the HTTP transport layer, so no model server is required.
"""

from __future__ import annotations

import base64
import io
import json
import os
import re
import sqlite3
import xml.etree.ElementTree as ET
import zipfile
from contextlib import asynccontextmanager

import httpx
import pytest
from PIL import Image

from main import RateLimiter, Settings, _load_dotenv, create_app

pytestmark = pytest.mark.asyncio

OLLAMA_TEXT = "# Receipt\n\n| Item | Price |\n| --- | --- |\n| Coffee | 4.50 |"


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


def png_bytes(width: int = 64, height: int = 32, mode: str = "RGB") -> bytes:
    image = Image.new(mode, (width, height), (240, 238, 230) if mode == "RGB" else None)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def b64_png() -> str:
    return base64.b64encode(png_bytes()).decode("ascii")


def respond(request: httpx.Request, status_code: int = 200, **kwargs) -> httpx.Response:
    return httpx.Response(status_code=status_code, request=request, **kwargs)


def ollama_handler(captured: dict | None = None):
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/tags":
            return respond(request, json={"models": [{"name": "qwen2.5vl:7b"}]})

        payload = json.loads(request.content or b"{}")
        if captured is not None:
            captured["payload"] = payload
            captured["path"] = request.url.path

        if payload.get("stream"):
            chunks = [
                json.dumps({"message": {"content": "Hello "}, "done": False}) + "\n",
                json.dumps({"message": {"content": "world"}, "done": False}) + "\n",
                json.dumps(
                    {
                        "message": {"content": ""},
                        "done": True,
                        "eval_count": 8,
                        "prompt_eval_count": 5,
                        "eval_duration": 400_000_000,
                    }
                )
                + "\n",
            ]
            return respond(
                request,
                status_code=200,
                content="".join(chunks).encode(),
                headers={"content-type": "application/x-ndjson"},
            )

        return respond(
            request,
            json={
                "message": {"role": "assistant", "content": OLLAMA_TEXT},
                "done": True,
                "eval_count": 42,
                "prompt_eval_count": 11,
                "eval_duration": 1_000_000_000,
            },
        )

    return handler


class ChunkStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes], error: Exception | None = None) -> None:
        self._chunks = chunks
        self._error = error

    async def __aiter__(self):
        for chunk in self._chunks:
            yield chunk
        if self._error is not None:
            raise self._error


@asynccontextmanager
async def client_for(handler, **settings_overrides):
    settings_overrides.setdefault("enable_windows_ocr", False)
    settings = Settings(**settings_overrides)
    transport = httpx.MockTransport(handler) if handler is not None else None
    app = create_app(settings, transport=transport)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        try:
            yield client, app
        finally:
            await app.state.http.aclose()
            await app.state.gemini_http.aclose()
            app.state.waitlist.close()


def parse_sse(body: str) -> list[tuple[str, dict]]:
    events: list[tuple[str, dict]] = []
    for block in body.split("\n\n"):
        event_name = None
        data_lines: list[str] = []
        for line in block.strip().splitlines():
            if line.startswith("event:"):
                event_name = line[len("event:") :].strip()
            elif line.startswith("data:"):
                data_lines.append(line[len("data:") :].lstrip())
        if event_name and data_lines:
            events.append((event_name, json.loads("\n".join(data_lines))))
    return events


# --------------------------------------------------------------------------- #
# Page
# --------------------------------------------------------------------------- #


async def test_workspace_renders_template_and_config():
    async with client_for(None) as (client, _):
        response = await client.get("/app")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    body = response.text
    assert "Lenscribe" in body
    assert "/api/transcribe/stream" in body
    assert "cropperjs" in body
    assert "marked@15" in body
    assert "qwen2.5vl:7b" in body
    assert "cdn.tailwindcss.com" not in body
    assert 'href="/static/app-utilities.css"' in body
    assert re.search(r'<input\b[^>]*id="fileInput"[^>]*\bmultiple\b', body)
    assert 'id="pageQueue"' in body
    assert 'id="recognizeAllBtn"' in body
    assert 'id="queueList"' in body
    assert 'files[0]' not in body


@pytest.mark.parametrize("stream", [False, True])
async def test_multiple_pages_keep_transcriptions_separate(stream):
    images = []

    def handler(request):
        if request.url.path == "/api/tags":
            return respond(request, json={"models": [{"name": "qwen2.5vl:7b"}]})
        payload = json.loads(request.content)
        images.append(payload["messages"][-1]["images"][0])
        text = f"Page {len(images)} text"
        result = {"message": {"content": text}, "done": True}
        if stream:
            return respond(
                request,
                content=(json.dumps(result) + "\n").encode(),
                headers={"content-type": "application/x-ndjson"},
            )
        return respond(request, json=result)

    texts = []
    async with client_for(handler) as (client, _):
        for width in (64, 80):
            response = await client.post(
                "/api/transcribe/stream" if stream else "/api/transcribe",
                json={
                    "image_base64": base64.b64encode(png_bytes(width)).decode("ascii"),
                    "engine": "ollama",
                },
            )
            assert response.status_code == 200
            if stream:
                events = parse_sse(response.text)
                assert events[-1][0] == "done"
                texts.append("".join(data["text"] for event, data in events if event == "delta"))
            else:
                texts.append(response.json()["text"])

    assert texts == ["Page 1 text", "Page 2 text"]
    assert len(images) == 2
    assert images[0] != images[1]


async def test_index_is_homepage_with_pricing_waitlist_and_no_runtime_tailwind():
    async with client_for(None) as (client, _):
        response = await client.get("/")

    assert response.status_code == 200
    assert 'href="/app"' in response.text
    assert 'id="pricing"' in response.text
    assert 'id="waitlistForm"' in response.text
    assert "PLANNED" in response.text
    assert "not available for purchase" in response.text
    assert "cdn.tailwindcss.com" not in response.text
    assert 'src="/static/site.js"' in response.text
    assert "SoftwareApplication" in response.text


@pytest.mark.parametrize("path", ["/", "/app", "/privacy", "/terms"])
async def test_page_scripts_have_matching_security_nonce(path):
    async with client_for(None) as (client, _):
        response = await client.get(path)
        another = await client.get(path)

    assert response.status_code == 200
    policy = response.headers["content-security-policy"]
    nonce = re.search(r"'nonce-([^']+)'", policy).group(1)
    assert nonce not in another.headers["content-security-policy"]
    for attributes, content in re.findall(
        r"<script([^>]*)>(.*?)</script>", response.text, re.S
    ):
        if "src=" not in attributes and 'type="application/json"' not in attributes:
            assert f'nonce="{nonce}"' in attributes
    assert "frame-ancestors 'none'" in policy
    assert "'unsafe-eval'" not in policy
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-frame-options"] == "DENY"
    assert "camera=(self)" in response.headers["permissions-policy"]
    assert "strict-transport-security" not in response.headers


async def test_legal_pages_contact_and_brand_are_configurable():
    async with client_for(
        None, brand_name="Scan & Co", contact_email="support@example.com"
    ) as (client, _):
        privacy = await client.get("/privacy")
        terms = await client.get("/terms")

    assert "Privacy Policy" in privacy.text
    assert "Terms of Service" in terms.text
    assert "Scan &amp; Co" in privacy.text
    assert 'href="mailto:support@example.com"' in terms.text
    assert "Google's Gemini API" in privacy.text
    assert "Local preview:" not in privacy.text


async def test_legal_pages_flag_missing_operator_configuration():
    async with client_for(None) as (client, _):
        response = await client.get("/privacy")
    assert "Local preview:" in response.text


@pytest.mark.parametrize(
    "path", ["/static/site.css", "/static/site.js", "/static/app-utilities.css"]
)
async def test_marketing_assets_are_served(path):
    async with client_for(None) as (client, _):
        response = await client.get(path)
    assert response.status_code == 200
    assert len(response.content) > 500
    assert response.headers["x-content-type-options"] == "nosniff"


async def test_custom_html_404_preserves_api_errors():
    async with client_for(None) as (client, _):
        page = await client.get("/missing-page", headers={"accept": "text/html"})
        api = await client.get("/api/missing", headers={"accept": "text/html"})
    assert page.status_code == 404
    assert "We couldn't find that page" in page.text
    assert 'content="noindex"' in page.text
    assert api.status_code == 404
    assert api.json()["error"]["code"] == "not_found"


async def test_seo_urls_use_configured_public_origin():
    async with client_for(None, public_base_url="https://example.com") as (client, _):
        landing = await client.get("/")
        robots = await client.get("/robots.txt")
        sitemap = await client.get("/sitemap.xml")
        manifest = await client.get("/manifest.webmanifest")
        favicon = await client.get("/favicon.svg")
        health = await client.get("/healthz")

    assert 'rel="canonical" href="https://example.com/"' in landing.text
    assert 'content="https://example.com/og.png"' in landing.text
    assert landing.headers["strict-transport-security"].startswith("max-age=")
    assert "Sitemap: https://example.com/sitemap.xml" in robots.text
    urls = ET.fromstring(sitemap.text).findall(
        "{http://www.sitemaps.org/schemas/sitemap/0.9}url"
    )
    assert len(urls) == 4
    assert all(list(url)[0].text.startswith("https://example.com/") for url in urls)
    assert manifest.json()["start_url"] == "/app"
    assert manifest.headers["content-type"].startswith("application/manifest+json")
    assert favicon.headers["content-type"].startswith("image/svg+xml")
    assert health.json() == {"status": "ok", "version": "1.3.0"}


async def test_social_image_is_cached_png():
    async with client_for(None) as (client, _):
        first = await client.get("/og.png")
        second = await client.get("/og.png")
    assert first.status_code == 200
    assert first.headers["content-type"] == "image/png"
    assert first.content == second.content
    assert Image.open(io.BytesIO(first.content)).size == (1200, 630)


async def test_waitlist_normalizes_deduplicates_and_persists(tmp_path):
    async with client_for(None, data_dir=str(tmp_path)) as (client, app):
        first = await client.post(
            "/api/waitlist", json={"email": " PERSON@Example.com ", "plan": "pro"}
        )
        repeat = await client.post(
            "/api/waitlist", json={"email": "person@example.com", "plan": "pro"}
        )
        assert first.json() == repeat.json()
        assert first.headers["cache-control"] == "no-store"
        assert app.state.waitlist.count() == 1
    async with client_for(None, data_dir=str(tmp_path)) as (_, app):
        assert app.state.waitlist.count() == 1
    assert (tmp_path / "lenscribe.db").exists()


@pytest.mark.parametrize(
    "payload,code",
    [
        ({}, "invalid_email"),
        ({"email": "bad"}, "invalid_email"),
        ({"email": "a..b@example.com"}, "invalid_email"),
        ({"email": "a@example.com\r\nInjected: yes"}, "invalid_email"),
        ({"email": "a" * 65 + "@example.com"}, "invalid_email"),
        ({"email": ["a@example.com"]}, "invalid_email"),
        ({"email": "a@example.com", "plan": "free"}, "unsupported_plan"),
        (["a@example.com"], "invalid_json"),
    ],
)
async def test_waitlist_rejects_invalid_input(payload, code):
    async with client_for(None) as (client, app):
        response = await client.post("/api/waitlist", json=payload)
        assert app.state.waitlist.count() == 0
    assert response.status_code == 400
    assert response.json()["error"]["code"] == code


async def test_waitlist_body_limits_and_content_type():
    async with client_for(None) as (client, _):
        oversized = await client.post(
            "/api/waitlist", json={"email": "a@example.com", "website": "a" * 4096}
        )
        malformed = await client.post(
            "/api/waitlist", content=b"{", headers={"content-type": "application/json"}
        )
        wrong_type = await client.post("/api/waitlist", content="a@example.com")
    assert oversized.status_code == 413
    assert malformed.status_code == 400
    assert wrong_type.status_code == 415


async def test_waitlist_honeypot_does_not_store_email():
    async with client_for(None) as (client, app):
        response = await client.post(
            "/api/waitlist",
            json={"email": "bot@example.com", "plan": "team", "website": "spam"},
        )
        assert app.state.waitlist.count() == 0
    assert response.status_code == 200
    assert response.json()["success"] is True


async def test_waitlist_rate_limit_is_separate_from_processing():
    async with client_for(None, waitlist_rate_limit=1) as (client, _):
        first = await client.post("/api/waitlist", json={"email": "a@example.com"})
        second = await client.post("/api/waitlist", json={"email": "b@example.com"})
        export = await client.post("/api/export/txt", json={"markdown": "Hello"})
    assert first.status_code == 200
    assert second.status_code == 429
    assert int(second.headers["retry-after"]) > 0
    assert export.status_code == 200


async def test_admin_waitlist_export_disabled_without_token():
    async with client_for(None) as (client, _):
        response = await client.get("/api/admin/waitlist.csv")
    assert response.status_code == 404


async def test_admin_waitlist_export_requires_bearer_and_escapes_formulas():
    async with client_for(None, admin_token="test-only-token") as (client, _):
        await client.post("/api/waitlist", json={"email": "+sum@example.com"})
        denied = await client.get("/api/admin/waitlist.csv")
        query = await client.get("/api/admin/waitlist.csv?token=test-only-token")
        accepted = await client.get(
            "/api/admin/waitlist.csv",
            headers={"authorization": "Bearer test-only-token"},
        )
    assert denied.status_code == query.status_code == 401
    assert accepted.status_code == 200
    assert "email,plan,joined_at" in accepted.text
    assert "'+sum@example.com" in accepted.text
    assert "test-only-token" not in accepted.text
    assert accepted.headers["cache-control"] == "no-store"


async def test_rate_limiter_prunes_inactive_clients(monkeypatch):
    clock = [1.0]
    monkeypatch.setattr("main.time.monotonic", lambda: clock[0])
    limiter = RateLimiter(1, 60.0)
    for index in range(2048):
        assert await limiter.hit(str(index)) is None
    clock[0] = 100.0
    assert await limiter.hit("new-client") is None
    assert list(limiter._hits) == ["new-client"]
    assert await limiter.hit("new-client") == 60.0


async def test_lifespan_exception_closes_all_resources():
    app = create_app(Settings(enable_windows_ocr=False))
    with pytest.raises(RuntimeError, match="interrupted"):
        async with app.router.lifespan_context(app):
            raise RuntimeError("interrupted")
    assert app.state.http.is_closed
    assert app.state.gemini_http.is_closed
    with pytest.raises(sqlite3.ProgrammingError):
        app.state.waitlist.count()


async def test_lifespan_cleanup_continues_when_one_close_fails(monkeypatch):
    app = create_app(Settings(enable_windows_ocr=False))
    close = app.state.gemini_http.aclose

    async def failing_close():
        await close()
        raise RuntimeError("close failed")

    monkeypatch.setattr(app.state.gemini_http, "aclose", failing_close)
    with pytest.raises(RuntimeError, match="close failed"):
        async with app.router.lifespan_context(app):
            pass
    assert app.state.http.is_closed
    assert app.state.gemini_http.is_closed
    with pytest.raises(sqlite3.ProgrammingError):
        app.state.waitlist.count()


# --------------------------------------------------------------------------- #
# Successful transcription
# --------------------------------------------------------------------------- #


async def test_transcribe_multipart_returns_text_and_stats():
    captured: dict = {}
    png = png_bytes()
    async with client_for(ollama_handler(captured)) as (client, _):
        response = await client.post(
            "/api/transcribe",
            files={"image": ("receipt.png", png, "image/png")},
            data={"prompt": "Extract the receipt table."},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["text"] == OLLAMA_TEXT
    assert body["provider"] == "ollama"
    assert body["model"] == "qwen2.5vl:7b"
    assert body["image"] == {"width": 64, "height": 32, "bytes": len(png)}
    assert body["stats"]["eval_count"] == 42
    assert body["stats"]["tokens_per_second"] == 42.0

    payload = captured["payload"]
    assert captured["path"] == "/api/chat"
    assert payload["stream"] is False
    assert payload["model"] == "qwen2.5vl:7b"
    assert payload["messages"][0]["role"] == "system"
    user_message = payload["messages"][1]
    assert user_message["content"] == "Extract the receipt table."
    assert user_message["images"][0] == base64.b64encode(
        base64.b64decode(user_message["images"][0])
    ).decode()


async def test_transcribe_accepts_json_base64_and_data_uri():
    captured: dict = {}
    data_uri = "data:image/png;base64," + b64_png()
    async with client_for(ollama_handler(captured)) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": data_uri, "prompt": ""},
        )

    assert response.status_code == 200
    assert response.json()["text"] == OLLAMA_TEXT
    assert captured["payload"]["messages"][1]["content"].startswith("Transcribe all text")


async def test_transcribe_accepts_file_field_alias():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            files={"file": ("photo.png", png_bytes(), "image/png")},
        )

    assert response.status_code == 200
    assert response.json()["success"] is True


async def test_rgba_image_is_flattened_to_jpeg_for_ollama():
    captured: dict = {}
    rgba = png_bytes(40, 20, mode="RGBA")
    async with client_for(ollama_handler(captured)) as (client, _):
        response = await client.post(
            "/api/transcribe",
            files={"image": ("alpha.png", rgba, "image/png")},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["image"]["width"] == 40
    assert body["image"]["height"] == 20
    encoded = captured["payload"]["messages"][1]["images"][0]
    assert base64.b64decode(encoded)[:3] == b"\xff\xd8\xff"


# --------------------------------------------------------------------------- #
# Validation errors
# --------------------------------------------------------------------------- #


async def test_rejects_non_image_payload():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            files={"image": ("notes.txt", b"just text, not an image", "image/png")},
        )

    assert response.status_code == 415
    error = response.json()["error"]
    assert error["code"] == "unsupported_media_type"
    assert "not a readable image" in error["message"]


async def test_rejects_empty_upload():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            files={"image": ("empty.png", b"", "image/png")},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "empty_image"


async def test_rejects_oversized_upload():
    async with client_for(ollama_handler(), max_upload_bytes=1024) as (client, _):
        response = await client.post(
            "/api/transcribe",
            files={"image": ("big.png", b"\x89PNG" + b"0" * 2048, "image/png")},
        )

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "file_too_large"


async def test_missing_image_field_is_json_error():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe", files={"prompt": (None, "hello")}
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "missing_image"


async def test_invalid_json_body():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            content=b"{not json",
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_json"


async def test_invalid_base64_is_rejected():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe", json={"image_base64": "definitely not base64!!"}
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_image_data"


async def test_non_string_prompt_is_rejected():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe", json={"image_base64": b64_png(), "prompt": 42}
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_prompt"


async def test_prompt_too_long_is_rejected():
    async with client_for(ollama_handler(), max_prompt_chars=100) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "prompt": "x" * 101},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "prompt_too_long"


async def test_unsupported_content_type_is_rejected():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            content=b"image=hello",
            headers={"content-type": "application/x-www-form-urlencoded"},
        )

    assert response.status_code == 415
    assert response.json()["error"]["code"] == "unsupported_content_type"


# --------------------------------------------------------------------------- #
# Rate limiting and upstream failures
# --------------------------------------------------------------------------- #


async def test_rate_limit_returns_429_with_retry_after():
    async with client_for(
        ollama_handler(), rate_limit_requests=2, rate_limit_window=60
    ) as (client, _):
        payload = {"image_base64": b64_png()}
        first = await client.post("/api/transcribe", json=payload)
        second = await client.post("/api/transcribe", json=payload)
        third = await client.post("/api/transcribe", json=payload)

    assert first.status_code == 200
    assert second.status_code == 200
    assert third.status_code == 429
    assert int(third.headers["retry-after"]) >= 1
    assert third.json()["error"]["code"] == "rate_limited"


async def test_ollama_unreachable_maps_to_503():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async with client_for(handler) as (client, _):
        response = await client.post(
            "/api/transcribe", json={"image_base64": b64_png()}
        )

    assert response.status_code == 503
    error = response.json()["error"]
    assert error["code"] == "ollama_unreachable"
    assert "ollama serve" in error["hint"]


async def test_missing_model_maps_to_503_with_pull_hint():
    async def handler(request: httpx.Request) -> httpx.Response:
        return respond(request, status_code=404, json={"error": "model not found"})

    async with client_for(handler) as (client, _):
        response = await client.post(
            "/api/transcribe", json={"image_base64": b64_png()}
        )

    assert response.status_code == 503
    error = response.json()["error"]
    assert error["code"] == "model_unavailable"
    assert "ollama pull" in error["hint"]


async def test_upstream_rate_limit_is_passed_through():
    async def handler(request: httpx.Request) -> httpx.Response:
        return respond(
            request,
            status_code=429,
            headers={"retry-after": "7"},
            json={"error": "busy"},
        )

    async with client_for(handler) as (client, _):
        response = await client.post(
            "/api/transcribe", json={"image_base64": b64_png()}
        )

    assert response.status_code == 429
    assert response.headers["retry-after"] == "7"
    assert response.json()["error"]["code"] == "upstream_rate_limited"


async def test_model_timeout_maps_to_504():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("model timed out", request=request)

    async with client_for(handler) as (client, _):
        response = await client.post(
            "/api/transcribe", json={"image_base64": b64_png()}
        )

    assert response.status_code == 504
    assert response.json()["error"]["code"] == "timeout"


# --------------------------------------------------------------------------- #
# Streaming
# --------------------------------------------------------------------------- #


async def test_stream_emits_meta_deltas_and_done():
    captured: dict = {}
    async with client_for(ollama_handler(captured)) as (client, _):
        response = await client.post(
            "/api/transcribe/stream",
            json={"image_base64": b64_png(), "prompt": "Read this."},
        )

    assert response.status_code == 200
    assert "text/event-stream" in response.headers["content-type"]
    assert response.headers["cache-control"] == "no-cache, no-transform"

    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names[0] == "meta"
    assert names[-1] == "done"
    assert "delta" in names

    meta = events[0][1]
    assert meta["model"] == "qwen2.5vl:7b"
    assert "".join(data["text"] for name, data in events if name == "delta") == "Hello world"

    done = events[-1][1]
    assert done["stats"]["eval_count"] == 8
    assert done["stats"]["tokens_per_second"] == 20.0
    assert isinstance(done["elapsed_ms"], int)

    assert captured["payload"]["stream"] is True


async def test_stream_preflight_error_returns_json_status():
    async def handler(request: httpx.Request) -> httpx.Response:
        return respond(request, status_code=404, json={"error": "missing model"})

    async with client_for(handler) as (client, _):
        response = await client.post(
            "/api/transcribe/stream", json={"image_base64": b64_png()}
        )

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/json")
    assert response.json()["error"]["code"] == "model_unavailable"


async def test_stream_midstream_timeout_emits_error_event():
    line = json.dumps({"message": {"content": "partial"}, "done": False}) + "\n"

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/tags":
            return respond(request, json={"models": []})
        stream = ChunkStream(
            [line.encode()],
            error=httpx.ReadTimeout("stream stalled", request=request),
        )
        return respond(
            request,
            status_code=200,
            stream=stream,
            headers={"content-type": "application/x-ndjson"},
        )

    async with client_for(handler) as (client, _):
        response = await client.post(
            "/api/transcribe/stream", json={"image_base64": b64_png()}
        )

    assert response.status_code == 200
    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names[0] == "meta"
    assert "delta" in names
    assert names[-1] == "error"
    assert events[-1][1]["error"]["code"] == "timeout"


async def test_stream_respects_rate_limit():
    async with client_for(
        ollama_handler(), rate_limit_requests=1, rate_limit_window=60
    ) as (client, _):
        first = await client.post(
            "/api/transcribe/stream", json={"image_base64": b64_png()}
        )
        second = await client.post(
            "/api/transcribe/stream", json={"image_base64": b64_png()}
        )

    assert first.status_code == 200
    assert second.status_code == 429
    assert second.json()["error"]["code"] == "rate_limited"


# --------------------------------------------------------------------------- #
# Health
# --------------------------------------------------------------------------- #


async def test_health_reports_online_model():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.get("/api/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "online"
    assert body["model_present"] is True
    assert body["offline"] is True


async def test_health_reports_offline_without_ollama():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    async with client_for(handler) as (client, _):
        response = await client.get("/api/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "offline"
    assert body["model_present"] is False
    assert body["engines"]["ollama"]["available"] is False
    assert body["engines"]["windows"]["available"] is False


# --------------------------------------------------------------------------- #
# Windows OCR engine
# --------------------------------------------------------------------------- #


async def test_windows_engine_transcribes_without_ollama():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    async with client_for(handler, enable_windows_ocr=True) as (client, app):
        async def fake_info(refresh: bool = False) -> dict:
            return {"available": True, "language": "en-US", "languages": ["en-US"]}

        async def fake_recognize(image_bytes: bytes) -> dict:
            assert image_bytes
            return {
                "text": "Hello from Windows",
                "lines": ["Hello from Windows"],
                "language": "en-US",
                "elapsed_ms": 12,
            }

        app.state.windows_ocr.info = fake_info
        app.state.windows_ocr.recognize = fake_recognize

        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "windows"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["text"] == "Hello from Windows"
    assert body["provider"] == "windows"
    assert body["engine"] == "windows"
    assert body["model"] == "Windows OCR"
    assert body["stats"]["language"] == "en-US"


async def test_windows_engine_stream_emits_text():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    async with client_for(handler) as (client, app):
        async def fake_info(refresh: bool = False) -> dict:
            return {"available": True, "language": "en-US", "languages": ["en-US"]}

        async def fake_recognize(image_bytes: bytes) -> dict:
            return {
                "text": "Line one\nLine two",
                "lines": ["Line one", "Line two"],
                "language": "en-US",
                "elapsed_ms": 9,
            }

        app.state.windows_ocr.info = fake_info
        app.state.windows_ocr.recognize = fake_recognize

        response = await client.post(
            "/api/transcribe/stream",
            json={"image_base64": b64_png(), "engine": "windows"},
        )

    assert response.status_code == 200
    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names == ["meta", "delta", "done"]
    assert events[0][1]["engine"] == "windows"
    assert events[1][1]["text"] == "Line one\nLine two"
    assert events[2][1]["stats"]["lines"] == 2
    assert events[2][1]["elapsed_ms"] >= 0


async def test_windows_engine_unavailable_returns_503():
    async with client_for(
        ollama_handler(), ocr_engine="windows", enable_windows_ocr=False
    ) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "windows"},
        )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "windows_ocr_unavailable"


async def test_invalid_engine_is_rejected():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "magic"},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_engine"


# --------------------------------------------------------------------------- #
# Word export
# --------------------------------------------------------------------------- #


async def test_export_docx_returns_word_document():
    markdown = (
        "# Receipt\n\n"
        "**Coffee** and a `card` payment.\n\n"
        "| Item | Price |\n| --- | --- |\n| Coffee | 4.50 |\n\n"
        "- One\n- Two\n"
    )
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/export/docx",
            json={"markdown": markdown, "title": "Receipt scan"},
        )

    assert response.status_code == 200
    assert response.headers["content-type"] == (
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    assert "attachment;" in response.headers["content-disposition"]
    assert "filename*=UTF-8''Receipt-scan.docx" in response.headers["content-disposition"]

    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert archive.testzip() is None
        document = archive.read("word/document.xml").decode("utf-8")
        assert "Receipt" in document
        assert "<w:tbl>" in document
        assert "Coffee" in document
        assert archive.read("word/styles.xml")


async def test_export_docx_rejects_empty_document():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post("/api/export/docx", json={"markdown": "  "})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "empty_document"


# --------------------------------------------------------------------------- #
# PDF, spreadsheet, slides, and plain-text exports
# --------------------------------------------------------------------------- #

EXPORT_MARKDOWN = (
    "# Grocery run\n\n"
    "**Coffee** and a `card` payment.\n\n"
    "| Item | Price |\n| --- | --- |\n| Coffee | 4.50 |\n| Bread | 2.10 |\n\n"
    "- Milk\n- Eggs\n"
)


@pytest.mark.parametrize(
    ("fmt", "media_type", "extension"),
    [
        (
            "pdf",
            "application/pdf",
            "pdf",
        ),
        (
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsx",
        ),
        (
            "pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "pptx",
        ),
        ("csv", "text/csv; charset=utf-8", "csv"),
        ("txt", "text/plain; charset=utf-8", "txt"),
    ],
)
async def test_export_formats_return_expected_media_types(fmt, media_type, extension):
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            f"/api/export/{fmt}",
            json={"markdown": EXPORT_MARKDOWN, "title": "Grocery run"},
        )

    assert response.status_code == 200
    assert response.headers["content-type"] == media_type
    disposition = response.headers["content-disposition"]
    assert "attachment;" in disposition
    assert f"filename*=UTF-8''Grocery-run.{extension}" in disposition
    assert len(response.content) > (20 if fmt == "csv" else 100)


async def test_export_pdf_is_a_valid_pdf():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/export/pdf", json={"markdown": EXPORT_MARKDOWN, "title": "Grocery"}
        )

    body = response.content
    assert body.startswith(b"%PDF-1.4")
    assert body.rstrip().endswith(b"%%EOF")
    assert b"/Type /Catalog" in body
    assert b"Coffee" in body


async def test_export_xlsx_contains_transcript_and_tables():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/export/xlsx", json={"markdown": EXPORT_MARKDOWN, "title": "Grocery"}
        )

    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert archive.testzip() is None
        names = set(archive.namelist())
        assert "xl/workbook.xml" in names
        assert "xl/worksheets/sheet1.xml" in names
        workbook = archive.read("xl/workbook.xml").decode("utf-8")
        first_sheet = archive.read("xl/worksheets/sheet1.xml").decode("utf-8")
    assert "Table 1" in workbook
    assert "Grocery run" in first_sheet
    assert "Coffee" in first_sheet


async def test_export_pptx_contains_slides():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/export/pptx", json={"markdown": EXPORT_MARKDOWN, "title": "Grocery"}
        )

    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert archive.testzip() is None
        names = set(archive.namelist())
        assert "ppt/presentation.xml" in names
        assert "ppt/slides/slide1.xml" in names
        assert "ppt/theme/theme1.xml" in names
        slide = archive.read("ppt/slides/slide1.xml").decode("utf-8")
    assert "Grocery" in slide


async def test_export_txt_and_csv_preserve_text():
    async with client_for(ollama_handler()) as (client, _):
        txt = await client.post("/api/export/txt", json={"markdown": EXPORT_MARKDOWN})
        csv_response = await client.post(
            "/api/export/csv", json={"markdown": EXPORT_MARKDOWN}
        )

    text = txt.content.decode("utf-8")
    assert "Coffee" in text
    assert "- Milk" in text

    assert csv_response.content.startswith(b"\xef\xbb\xbf")
    rows = csv_response.content.decode("utf-8-sig").splitlines()
    assert "Item,Price" in rows
    assert "Coffee,4.50" in rows
    assert "Grocery run" not in rows


async def test_export_rejects_unknown_format():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/export/md", json={"markdown": "# nope"}
        )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "unsupported_format"


async def test_export_requires_json_content_type():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/export/pdf",
            content=b"markdown=hi",
            headers={"content-type": "text/plain"},
        )

    assert response.status_code == 415
    assert response.json()["error"]["code"] == "unsupported_media_type"


# --------------------------------------------------------------------------- #
# Text to image card
# --------------------------------------------------------------------------- #

CARD_TEXT = (
    "# Market receipt\n\n"
    "**Coffee** 4.50 and a `card` payment.\n\n"
    "- Cardamom bun\n- Filter coffee\n"
)


async def test_text_image_returns_png_card():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/text-image",
            json={"text": CARD_TEXT, "title": "Market receipt"},
        )

    assert response.status_code == 200
    assert response.headers["content-type"] == "image/png"
    disposition = response.headers["content-disposition"]
    assert "attachment;" in disposition
    assert "filename*=UTF-8''Market-receipt.png" in disposition
    assert response.headers["x-card-theme"] == "emerald"
    assert response.headers["x-card-aspect"] == "square"
    assert response.content.startswith(b"\x89PNG\r\n\x1a\n")
    image = Image.open(io.BytesIO(response.content))
    assert image.size == (1080, 1080)


@pytest.mark.parametrize(
    ("aspect", "size"),
    [("portrait", (1080, 1350)), ("wide", (1200, 630))],
)
async def test_text_image_supports_sizes(aspect, size):
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/text-image", json={"text": CARD_TEXT, "aspect": aspect}
        )

    assert response.status_code == 200
    assert response.headers["x-card-aspect"] == aspect
    assert Image.open(io.BytesIO(response.content)).size == size


async def test_text_image_themes_render_distinct_cards():
    payloads = {}
    async with client_for(ollama_handler()) as (client, _):
        for theme in ("emerald", "midnight", "paper"):
            response = await client.post(
                "/api/text-image", json={"text": CARD_TEXT, "theme": theme}
            )
            assert response.status_code == 200
            assert response.headers["x-card-theme"] == theme
            payloads[theme] = response.content

    assert len({payloads[theme] for theme in payloads}) == 3
    paper = Image.open(io.BytesIO(payloads["paper"])).convert("RGB")
    emerald = Image.open(io.BytesIO(payloads["emerald"])).convert("RGB")
    assert sum(paper.getpixel((8, 8))) > sum(emerald.getpixel((8, 8)))


@pytest.mark.parametrize(
    ("payload", "status", "code"),
    [
        ({"text": "   "}, 400, "empty_card_text"),
        ({"text": "x" * 12_001}, 413, "card_text_too_long"),
        ({"text": "hello", "theme": "neon"}, 400, "unsupported_theme"),
        ({"text": "hello", "aspect": "tall"}, 400, "unsupported_aspect"),
        ({"text": "hello", "title": 7}, 200, None),
    ],
)
async def test_text_image_validates_input(payload, status, code):
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post("/api/text-image", json=payload)

    assert response.status_code == status
    if code is not None:
        assert response.json()["error"]["code"] == code


async def test_text_image_requires_json_content_type():
    async with client_for(ollama_handler()) as (client, _):
        response = await client.post(
            "/api/text-image",
            content=b"text=hi",
            headers={"content-type": "text/plain"},
        )

    assert response.status_code == 415
    assert response.json()["error"]["code"] == "unsupported_media_type"


# --------------------------------------------------------------------------- #
# Gemini engine
# --------------------------------------------------------------------------- #

GEMINI_TEXT = "# Gemini receipt\n\nTotal 9.99"


def gemini_handler(
    captured: dict | None = None, *, status_code: int = 200
):
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/tags":
            raise httpx.ConnectError("refused", request=request)
        if captured is not None:
            captured["path"] = request.url.path
            captured["key"] = request.headers.get("x-goog-api-key")
            captured["payload"] = json.loads(request.content or b"{}")

        if status_code != 200:
            return respond(
                request,
                status_code=status_code,
                json={"error": {"message": "API key not valid", "status": "INVALID"}},
            )

        if "streamGenerateContent" in request.url.path:
            chunks = [
                {"candidates": [{"content": {"parts": [{"text": "Gem"}]}}]},
                {
                    "candidates": [{"content": {"parts": [{"text": "ini"}]}}],
                    "usageMetadata": {
                        "promptTokenCount": 5,
                        "candidatesTokenCount": 7,
                        "totalTokenCount": 12,
                    },
                },
            ]
            content = "".join(
                "data: " + json.dumps(chunk) + "\n\n" for chunk in chunks
            )
            return respond(
                request,
                content=content.encode(),
                headers={"content-type": "text/event-stream"},
            )

        return respond(
            request,
            json={
                "candidates": [
                    {
                        "content": {"parts": [{"text": GEMINI_TEXT}]},
                        "finishReason": "STOP",
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 11,
                    "candidatesTokenCount": 21,
                    "totalTokenCount": 32,
                },
            },
        )

    return handler


async def test_gemini_transcribes_with_request_key():
    captured: dict = {}
    async with client_for(gemini_handler(captured), enable_windows_ocr=True) as (
        client,
        _,
    ):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "gemini", "prompt": "Read it."},
            headers={"x-gemini-key": "user-key-123"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["text"] == GEMINI_TEXT
    assert body["provider"] == "gemini"
    assert body["engine"] == "gemini"
    assert body["model"] == "Gemini 3.5 Flash"
    assert body["stats"]["eval_count"] == 21

    assert captured["key"] == "user-key-123"
    assert "generateContent" in captured["path"]
    assert "gemini-3.5-flash" in captured["path"]
    payload = captured["payload"]
    assert payload["systemInstruction"]["parts"][0]["text"]
    parts = payload["contents"][0]["parts"]
    assert parts[0]["inline_data"]["mime_type"] == "image/jpeg"
    assert parts[1]["text"] == "Read it."


async def test_gemini_uses_environment_key():
    captured: dict = {}
    async with client_for(
        gemini_handler(captured), gemini_api_key="env-key-456"
    ) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "gemini"},
        )

    assert response.status_code == 200
    assert captured["key"] == "env-key-456"


async def test_server_key_overrides_request_header():
    captured: dict = {}
    async with client_for(
        gemini_handler(captured), gemini_api_key="server-key"
    ) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "gemini"},
            headers={"x-gemini-key": "browser-key"},
        )

    assert response.status_code == 200
    assert captured["key"] == "server-key"


async def test_dotenv_loader_sets_missing_keys_only(tmp_path, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.setenv("LENS_EXISTING", "keep")
    env_file = tmp_path / ".env"
    env_file.write_text(
        "GEMINI_API_KEY=from-file\n"
        "LENS_EXISTING=overwritten\n"
        "# a comment\n"
        "NOT_A_PAIR\n",
        encoding="utf-8",
    )

    _load_dotenv(env_file)

    assert os.environ["GEMINI_API_KEY"] == "from-file"
    assert os.environ["LENS_EXISTING"] == "keep"


async def test_gemini_without_key_returns_actionable_error():
    async with client_for(gemini_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "gemini"},
        )

    assert response.status_code == 400
    error = response.json()["error"]
    assert error["code"] == "gemini_key_required"
    assert "aistudio.google.com/apikey" in error["hint"]


async def test_gemini_invalid_key_maps_to_401():
    async with client_for(gemini_handler(status_code=403)) as (client, _):
        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "gemini"},
            headers={"x-gemini-key": "bad-key"},
        )

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "gemini_key_invalid"


async def test_gemini_stream_emits_meta_deltas_and_done():
    captured: dict = {}
    async with client_for(gemini_handler(captured)) as (client, _):
        response = await client.post(
            "/api/transcribe/stream",
            json={"image_base64": b64_png(), "engine": "gemini"},
            headers={"x-gemini-key": "stream-key"},
        )

    assert response.status_code == 200
    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names == ["meta", "delta", "delta", "done"]
    assert events[0][1]["provider"] == "gemini"
    assert "".join(data["text"] for name, data in events if name == "delta") == "Gemini"
    assert events[-1][1]["stats"]["total_tokens"] == 12
    assert events[-1][1]["engine"] == "gemini"
    assert "streamGenerateContent" in captured["path"]


async def test_gemini_stream_without_key_returns_json_400():
    async with client_for(gemini_handler()) as (client, _):
        response = await client.post(
            "/api/transcribe/stream",
            json={"image_base64": b64_png(), "engine": "gemini"},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "gemini_key_required"


async def test_auto_engine_never_selects_gemini():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    async with client_for(
        handler, gemini_api_key="configured-key", enable_windows_ocr=False
    ) as (client, app):
        async def unavailable(refresh: bool = False) -> dict:
            return {"available": False, "reason": "no OCR language"}

        app.state.windows_ocr.info = unavailable

        response = await client.post(
            "/api/transcribe",
            json={"image_base64": b64_png(), "engine": "auto"},
        )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "ollama_unreachable"


async def test_health_reports_gemini_configuration():
    async with client_for(
        gemini_handler(), gemini_api_key="configured-key"
    ) as (client, _):
        response = await client.get("/api/health")

    assert response.status_code == 200
    gemini = response.json()["engines"]["gemini"]
    assert gemini["configured"] is True
    assert gemini["model"] == "gemini-3.5-flash"
    assert gemini["label"] == "Gemini 3.5 Flash"
    assert gemini["key_url"].startswith("https://aistudio.google.com")

