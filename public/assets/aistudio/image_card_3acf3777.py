"""Local text-to-image card renderer.

Turns Markdown-ish transcription text into a shareable PNG card using Pillow.
Everything runs on the host machine: no cloud calls and no new dependencies.
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from markdown_blocks import parse_blocks, plain_text

CARD_THEMES = ("emerald", "midnight", "paper")

CARD_ASPECTS = {
    "square": (1080, 1080),
    "portrait": (1080, 1350),
    "wide": (1200, 630),
}

Color = tuple[int, int, int]
RGBA = tuple[int, int, int, int]


@dataclass(frozen=True, slots=True)
class Palette:
    top: Color
    bottom: Color
    glow: RGBA
    grid: RGBA
    ink: Color
    muted: Color
    accent: Color
    label: Color
    border: RGBA


PALETTES = {
    "emerald": Palette(
        top=(9, 26, 22),
        bottom=(3, 7, 9),
        glow=(16, 185, 129, 62),
        grid=(255, 255, 255, 9),
        ink=(236, 253, 245),
        muted=(148, 163, 184),
        accent=(52, 211, 153),
        label=(110, 231, 183),
        border=(255, 255, 255, 20),
    ),
    "midnight": Palette(
        top=(11, 20, 38),
        bottom=(4, 6, 10),
        glow=(14, 165, 233, 58),
        grid=(255, 255, 255, 8),
        ink=(226, 232, 240),
        muted=(148, 163, 184),
        accent=(56, 189, 248),
        label=(125, 211, 252),
        border=(255, 255, 255, 18),
    ),
    "paper": Palette(
        top=(250, 248, 242),
        bottom=(240, 234, 223),
        glow=(52, 211, 153, 40),
        grid=(15, 23, 42, 8),
        ink=(22, 34, 29),
        muted=(88, 102, 95),
        accent=(5, 150, 105),
        label=(4, 120, 87),
        border=(22, 34, 29, 22),
    ),
}

_FONT_CANDIDATES = {
    "regular": (
        "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/calibri.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ),
    "bold": (
        "C:/Windows/Fonts/segoeuib.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
        "C:/Windows/Fonts/calibrib.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ),
    "mono": (
        "C:/Windows/Fonts/consola.ttf",
        "C:/Windows/Fonts/cour.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    ),
}


@lru_cache(maxsize=64)
def _font(style: str, size: int) -> ImageFont.ImageFont:
    for path in _FONT_CANDIDATES[style]:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def _width(font: ImageFont.ImageFont, text: str) -> float:
    try:
        return font.getlength(text)
    except AttributeError:
        return font.getbbox(text)[2]


def _line_height(font: ImageFont.ImageFont) -> int:
    ascent, descent = font.getmetrics()
    return ascent + descent


def _tracked_width(font: ImageFont.ImageFont, text: str, tracking: float) -> float:
    if not text:
        return 0.0
    return sum(_width(font, char) for char in text) + tracking * (len(text) - 1)


def _draw_tracked(
    draw: ImageDraw.ImageDraw,
    xy: tuple[float, float],
    text: str,
    font: ImageFont.ImageFont,
    fill: Color,
    tracking: float = 0.0,
) -> None:
    x, y = xy
    for char in text:
        draw.text((x, y), char, font=font, fill=fill)
        x += _width(font, char) + tracking


def _wrap(text: str, font: ImageFont.ImageFont, max_width: float) -> list[str]:
    lines: list[str] = []
    for raw in text.split("\n"):
        words = raw.split()
        if not words:
            lines.append("")
            continue
        current = ""
        for word in words:
            candidate = word if not current else f"{current} {word}"
            if _width(font, candidate) <= max_width:
                current = candidate
                continue
            if current:
                lines.append(current)
            if _width(font, word) <= max_width:
                current = word
                continue
            chunk = ""
            for char in word:
                if _width(font, chunk + char) <= max_width:
                    chunk += char
                else:
                    if chunk:
                        lines.append(chunk)
                    chunk = char
            current = chunk
        if current:
            lines.append(current)
    return lines or [""]


def _items(markdown: str) -> list[tuple[str, str, float]]:
    """Flatten parsed Markdown into (text, style, gap-before-em) items."""
    items: list[tuple[str, str, float]] = []
    for block in parse_blocks(markdown):
        gap = 0.34 if items else 0.0
        if block.kind == "heading":
            text = plain_text(block.text).strip()
            if text:
                items.append((text, "heading", 0.5 if items else 0.0))
        elif block.kind == "paragraph":
            text = plain_text(block.text).strip()
            if text:
                items.append((text, "body", gap))
        elif block.kind == "bullet":
            text = plain_text(block.text).strip()
            if text:
                items.append(("\u2022  " + text, "list", 0.1 if items else 0.0))
        elif block.kind == "number":
            text = plain_text(block.text).strip()
            if text:
                items.append(
                    (f"{block.number}.  {text}", "list", 0.1 if items else 0.0)
                )
        elif block.kind == "quote":
            text = plain_text(block.text).strip()
            if text:
                items.append(("\u201c" + text + "\u201d", "quote", gap))
        elif block.kind == "code":
            text = block.text.rstrip()
            if text.strip():
                items.append((text, "code", gap))
        elif block.kind == "table":
            for row in block.rows:
                line = "   ".join(plain_text(cell).strip() for cell in row)
                if line.strip():
                    items.append((line, "table", 0.1 if items else 0.0))
    return items


def _style(style: str, base: int, palette: Palette) -> tuple[ImageFont.ImageFont, Color]:
    if style == "heading":
        return _font("bold", base + 8), palette.ink
    if style == "quote":
        return _font("regular", base), palette.muted
    if style == "code":
        return _font("mono", max(18, base - 4)), palette.ink
    if style == "table":
        return _font("regular", max(18, base - 3)), palette.ink
    return _font("regular", base), palette.ink


def _layout(
    items: list[tuple[str, str, float]],
    base: int,
    max_width: float,
    max_height: float,
    palette: Palette,
) -> tuple[list[tuple[str, ImageFont.ImageFont, int, Color]], bool]:
    ops: list[tuple[str, ImageFont.ImageFont, int, Color]] = []
    y = 0.0
    for text, style, gap in items:
        font, color = _style(style, base, palette)
        if y and gap:
            y += gap * base
        line_height = _line_height(font) * 1.08
        for line in _wrap(text, font, max_width):
            if y + line_height > max_height:
                return ops, False
            ops.append((line, font, round(y), color))
            y += line_height
        y += (0.04 if style in {"list", "table"} else 0.14) * base
    return ops, True


def _fitted_lines(
    items: list[tuple[str, str, float]],
    max_width: float,
    max_height: float,
    palette: Palette,
    largest: int,
) -> list[tuple[str, ImageFont.ImageFont, int, Color]]:
    for base in range(largest, 19, -2):
        ops, fits = _layout(items, base, max_width, max_height, palette)
        if fits:
            return ops
    ops, _ = _layout(items, 20, max_width, max_height, palette)
    if not ops:
        return ops
    text, font, y, color = ops[-1]
    trimmed = text
    while trimmed and _width(font, trimmed + "\u2026") > max_width:
        trimmed = trimmed[:-1]
    ops[-1] = (trimmed.rstrip() + "\u2026", font, y, color)
    return ops


def _gradient(size: tuple[int, int], top: Color, bottom: Color) -> Image.Image:
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    width, height = size
    span = max(1, height - 1)
    for y in range(height):
        ratio = y / span
        color = tuple(
            round(top[channel] + (bottom[channel] - top[channel]) * ratio)
            for channel in range(3)
        )
        draw.line(((0, y), (width, y)), fill=color)
    return image


def _decorate(image: Image.Image, palette: Palette) -> Image.Image:
    width, height = image.size
    glow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse(
        (width * 0.52, -height * 0.32, width * 1.28, height * 0.38), fill=palette.glow
    )
    glow_draw.ellipse(
        (-width * 0.28, height * 0.58, width * 0.52, height * 1.24), fill=palette.glow
    )
    blur = max(48, round(min(width, height) * 0.12))
    glow = glow.filter(ImageFilter.GaussianBlur(blur))
    image = Image.alpha_composite(image.convert("RGBA"), glow)

    grid = Image.new("RGBA", image.size, (0, 0, 0, 0))
    grid_draw = ImageDraw.Draw(grid)
    spacing = 84
    for x in range(spacing, width, spacing):
        grid_draw.line(((x, 0), (x, height)), fill=palette.grid, width=1)
    for y in range(spacing, height, spacing):
        grid_draw.line(((0, y), (width, y)), fill=palette.grid, width=1)
    image = Image.alpha_composite(image, grid)

    border = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle(
        (26, 26, width - 26, height - 26),
        radius=30,
        outline=palette.border,
        width=2,
    )
    return Image.alpha_composite(image, border)


def _title_block(
    title: str, font_width: float
) -> tuple[list[str], ImageFont.ImageFont]:
    for size in (58, 50, 44, 38, 32):
        font = _font("bold", size)
        lines = _wrap(title, font, font_width)
        if len(lines) <= 2:
            return lines, font
    font = _font("bold", 32)
    lines = _wrap(title, font, font_width)[:2]
    last = lines[-1]
    while last and _width(font, last + "\u2026") > font_width:
        last = last[:-1]
    lines[-1] = last.rstrip() + "\u2026"
    return lines, font


def build_text_image(
    text: str,
    title: str | None = None,
    theme: str = "emerald",
    aspect: str = "square",
) -> bytes:
    """Render text into a PNG shareable card."""
    palette = PALETTES.get(theme, PALETTES["emerald"])
    width, height = CARD_ASPECTS.get(aspect, CARD_ASPECTS["square"])

    image = _decorate(_gradient((width, height), palette.top, palette.bottom), palette)
    draw = ImageDraw.Draw(image)

    wide = aspect == "wide"
    side = 72 if wide else 88
    top = 54 if wide else 72
    content_width = width - side * 2
    footer_y = height - (46 if wide else 60)

    eyebrow_font = _font("mono", 15)
    _draw_tracked(
        draw, (side + 24, top), "LENSCRIBE", eyebrow_font, palette.label, tracking=3.2
    )
    _draw_tracked(
        draw,
        (side + 24 + _tracked_width(eyebrow_font, "LENSCRIBE", 3.2) + 12, top),
        "/ TEXT CARD",
        eyebrow_font,
        palette.muted,
        tracking=3.2,
    )
    draw.ellipse((side, top + 4, side + 12, top + 16), fill=palette.accent)

    title_lines: list[str] = []
    title_font: ImageFont.ImageFont | None = None
    cursor = top + 40
    if title:
        title_lines, title_font = _title_block(title, content_width)
        title_height = _line_height(title_font) * 1.06
        for index, line in enumerate(title_lines):
            draw.text(
                (side, cursor + index * title_height),
                line,
                font=title_font,
                fill=palette.ink,
            )
        cursor += round(title_height * len(title_lines)) + 22
    else:
        cursor += 8

    draw.rounded_rectangle(
        (side, cursor, side + 62, cursor + 5), radius=2.5, fill=palette.accent
    )
    body_top = cursor + 42
    body_height = footer_y - body_top - 36
    body_width = content_width

    items = _items(text) or [("", "body", 0.0)]
    ops = _fitted_lines(
        items,
        body_width,
        max(60, body_height),
        palette,
        36 if wide else 46,
    )
    for line, font, y, color in ops:
        draw.text((side, body_top + y), line, font=font, fill=color)

    draw.line(
        ((side, footer_y - 24), (width - side, footer_y - 24)),
        fill=palette.border,
        width=1,
    )
    footer_font = _font("mono", 12)
    _draw_tracked(
        draw, (side, footer_y), "LENSCRIBE", footer_font, palette.label, tracking=2.6
    )
    right = "TEXT TO IMAGE / NO CLOUD"
    right_width = _tracked_width(footer_font, right, 2.6)
    _draw_tracked(
        draw,
        (width - side - right_width, footer_y),
        right,
        footer_font,
        palette.muted,
        tracking=2.6,
    )

    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()
