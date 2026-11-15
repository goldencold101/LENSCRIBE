"""Minimal Markdown-to-PDF writer with no third-party dependencies.

Supports the block types Lenscribe produces: headings, paragraphs, lists,
quotes, code blocks, tables, and horizontal rules. Uses the PDF base-14
Helvetica and Courier fonts, so no font files need to be embedded.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone

from markdown_blocks import Block, parse_blocks, plain_text

PAGE_W = 595.28
PAGE_H = 841.89
MARGIN = 54.0
BOTTOM = 52.0
CONTENT_W = PAGE_W - 2 * MARGIN

_INK = (0.12, 0.16, 0.22)
_MUTED = (0.36, 0.42, 0.48)
_ACCENT = (0.06, 0.46, 0.43)
_GRID = (0.80, 0.84, 0.87)
_CODE_BG = (0.96, 0.97, 0.98)
_HEAD_BG = (0.91, 0.96, 0.94)
_QUOTE_BG = (0.96, 0.97, 0.98)
_RULE = (0.80, 0.84, 0.87)

# Helvetica advance widths for ASCII 32-126 (per 1000 em).
_HELVETICA = (
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278,
    278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584,
    584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556,
    833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278,
    278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222,
    500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500,
    500, 334, 260, 334, 584,
)
_BOLD = (
    278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278,
    278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584,
    584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611,
    833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333,
    278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278,
    556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556,
    500, 389, 280, 389, 584,
)


def _char_width(char: str, bold: bool, mono: bool) -> int:
    if mono:
        return 600
    code = ord(char)
    if 32 <= code <= 126:
        return (_BOLD if bold else _HELVETICA)[code - 32]
    return 611 if bold else 556


def _measure(text: str, size: float, *, bold: bool = False, mono: bool = False) -> float:
    return sum(_char_width(ch, bold, mono) for ch in text) * size / 1000.0


def _wrap(
    text: str,
    size: float,
    width: float,
    *,
    bold: bool = False,
    mono: bool = False,
) -> list[str]:
    lines: list[str] = []
    for paragraph in text.split("\n"):
        words = paragraph.split()
        if not words:
            lines.append("")
            continue

        current = ""
        for word in words:
            if not current:
                current = word
            elif _measure(current + " " + word, size, bold=bold, mono=mono) <= width:
                current += " " + word
            else:
                lines.append(current)
                current = word

            while _measure(current, size, bold=bold, mono=mono) > width:
                cut = 1
                while (
                    cut < len(current)
                    and _measure(current[:cut], size, bold=bold, mono=mono) <= width
                ):
                    cut += 1
                cut = max(1, cut - 1)
                lines.append(current[:cut])
                current = current[cut:]

        if current:
            lines.append(current)
    return lines or [""]


def _pdf_string(text: str) -> str:
    chunks: list[str] = []
    for char in text:
        try:
            encoded = char.encode("cp1252")
        except UnicodeEncodeError:
            encoded = b"?"
        for byte in encoded:
            if byte in (0x28, 0x29, 0x5C):  # ( ) \
                chunks.append("\\" + chr(byte))
            elif 32 <= byte <= 126:
                chunks.append(chr(byte))
            else:
                chunks.append(f"\\{byte:03o}")
    return "".join(chunks)


def _color_ops(rgb: tuple[float, float, float], *, stroke: bool = False) -> str:
    operator = "RG" if stroke else "rg"
    return f"{rgb[0]:.3f} {rgb[1]:.3f} {rgb[2]:.3f} {operator}"


class _Writer:
    def __init__(self) -> None:
        self.pages: list[list[str]] = []
        self.ops: list[str] = []
        self.y = 0.0
        self.new_page()

    def new_page(self) -> None:
        self.ops = []
        self.pages.append(self.ops)
        self.y = PAGE_H - MARGIN

    def need(self, height: float) -> None:
        if self.y - height < BOTTOM:
            self.new_page()

    def text(
        self,
        x: float,
        baseline: float,
        text: str,
        *,
        size: float,
        bold: bool = False,
        mono: bool = False,
        italic: bool = False,
        color: tuple[float, float, float] = _INK,
    ) -> None:
        if not text:
            return
        font = "F3" if mono else "F4" if italic else "F2" if bold else "F1"
        self.ops.append(
            f"q {_color_ops(color)} BT /{font} {size:.2f} Tf "
            f"{x:.2f} {baseline:.2f} Td ({_pdf_string(text)}) Tj ET Q"
        )

    def rect(
        self,
        x: float,
        y: float,
        width: float,
        height: float,
        *,
        fill: tuple[float, float, float] | None = None,
        stroke: tuple[float, float, float] | None = None,
        line_width: float = 0.7,
    ) -> None:
        parts = ["q"]
        if fill:
            parts.append(_color_ops(fill))
        if stroke:
            parts.append(_color_ops(stroke, stroke=True))
            parts.append(f"{line_width:.2f} w")
        parts.append(f"{x:.2f} {y:.2f} {width:.2f} {height:.2f} re")
        parts.append("B" if fill and stroke else "f" if fill else "S")
        parts.append("Q")
        self.ops.append(" ".join(parts))

    def hline(self, x1: float, x2: float, y: float, color=_RULE, width: float = 0.7) -> None:
        self.ops.append(
            f"q {_color_ops(color, stroke=True)} {width:.2f} w "
            f"{x1:.2f} {y:.2f} m {x2:.2f} {y:.2f} l S Q"
        )


def _draw_lines(
    writer: _Writer,
    lines: list[str],
    x: float,
    width: float,
    *,
    size: float,
    leading: float,
    bold: bool = False,
    mono: bool = False,
    italic: bool = False,
    color: tuple[float, float, float] = _INK,
    bullet: str | None = None,
) -> None:
    hanging = x + 13.0 if bullet else x
    for index, line in enumerate(lines):
        writer.need(leading)
        if bullet and index == 0:
            writer.text(x, writer.y - size, bullet, size=size, bold=True, color=_ACCENT)
        writer.text(hanging, writer.y - size, line, size=size, bold=bold, mono=mono, italic=italic, color=color)
        writer.y -= leading


def _paragraph(
    writer: _Writer,
    text: str,
    *,
    size: float = 10.5,
    bold: bool = False,
    italic: bool = False,
    color: tuple[float, float, float] = _INK,
    gap: float = 8.0,
    x: float = MARGIN,
    width: float = CONTENT_W,
    bullet: str | None = None,
) -> None:
    lines = _wrap(text, size, width - (13.0 if bullet else 0.0), bold=bold)
    writer.need(size * 1.45)
    _draw_lines(
        writer,
        lines,
        x,
        width,
        size=size,
        leading=size * 1.42,
        bold=bold,
        italic=italic,
        color=color,
        bullet=bullet,
    )
    writer.y -= gap


def _heading(writer: _Writer, block: Block) -> None:
    text = plain_text(block.text).strip()
    if not text:
        return
    sizes = {1: 19.0, 2: 15.5, 3: 13.0, 4: 11.5, 5: 10.8, 6: 10.5}
    size = sizes.get(block.level, 10.5)
    before = {1: 14.0, 2: 12.0, 3: 10.0}.get(block.level, 8.0)
    writer.need(before + size * 2)
    writer.y -= before
    _paragraph(
        writer,
        text,
        size=size,
        bold=True,
        color=_ACCENT,
        gap=7.0 if block.level > 1 else 9.0,
    )


def _code(writer: _Writer, text: str) -> None:
    size = 8.8
    leading = 11.6
    for raw in (text.split("\n") if text else [""]):
        for line in _wrap(raw, size, CONTENT_W - 14, mono=True):
            writer.need(leading)
            writer.rect(MARGIN, writer.y - leading + 1.6, CONTENT_W, leading, fill=_CODE_BG)
            writer.text(MARGIN + 6, writer.y - size - 1.0, line, size=size, mono=True, color=(0.15, 0.22, 0.28))
            writer.y -= leading
    writer.y -= 6.0


def _quote(writer: _Writer, text: str) -> None:
    size = 10.2
    leading = 14.2
    lines = _wrap(text, size, CONTENT_W - 20, bold=False)
    height = leading * len(lines) + 8
    writer.need(height)
    writer.rect(MARGIN, writer.y - height, CONTENT_W, height, fill=_QUOTE_BG)
    writer.rect(MARGIN, writer.y - height, 3.0, height, fill=_ACCENT)
    writer.y -= 4.0
    _draw_lines(
        writer,
        lines,
        MARGIN + 12,
        CONTENT_W - 20,
        size=size,
        leading=leading,
        italic=True,
        color=_MUTED,
    )
    writer.y -= 6.0


def _table(writer: _Writer, block: Block) -> None:
    rows = block.rows
    if not rows:
        return
    columns = max(len(row) for row in rows)
    rows = [row + [""] * (columns - len(row)) for row in rows]

    size = 8.8
    leading = 11.4
    padding = 5.0
    natural: list[float] = []
    for column in range(columns):
        widest = max(_measure(plain_text(row[column]), size) for row in rows)
        natural.append(min(max(52.0, widest + 2 * padding), CONTENT_W * 0.5))

    total = sum(natural)
    if total <= CONTENT_W:
        widths = [width + (CONTENT_W - total) / columns for width in natural]
    else:
        widths = [width * CONTENT_W / total for width in natural]

    def layout(row: list[str], header: bool) -> tuple[list[list[str]], float]:
        wrapped = [
            _wrap(plain_text(row[column]), size, widths[column] - 2 * padding, bold=header)
            for column in range(columns)
        ]
        height = max(1, max(len(lines) for lines in wrapped)) * leading + 2 * padding
        return wrapped, height

    for index, row in enumerate(rows):
        wrapped, height = layout(row, index == 0)
        if writer.y - height < BOTTOM:
            writer.new_page()
            if index > 0:
                head_lines, head_height = layout(rows[0], True)
                _draw_table_row(writer, head_lines, widths, head_height, size, leading, padding, True)
        _draw_table_row(writer, wrapped, widths, height, size, leading, padding, index == 0)

    writer.y -= 10.0


def _draw_table_row(
    writer: _Writer,
    wrapped: list[list[str]],
    widths: list[float],
    height: float,
    size: float,
    leading: float,
    padding: float,
    header: bool,
) -> None:
    x = MARGIN
    top = writer.y
    if header:
        writer.rect(MARGIN, top - height, sum(widths), height, fill=_HEAD_BG)
    for column, lines in enumerate(wrapped):
        width = widths[column]
        writer.rect(x, top - height, width, height, stroke=_GRID)
        for line_index, line in enumerate(lines):
            baseline = top - padding - size - line_index * leading
            writer.text(
                x + padding,
                baseline,
                line,
                size=size,
                bold=header,
                color=(0.09, 0.13, 0.17) if header else _INK,
            )
        x += width
    writer.y -= height


def _rule(writer: _Writer) -> None:
    writer.need(12)
    writer.y -= 4
    writer.hline(MARGIN, MARGIN + CONTENT_W, writer.y)
    writer.y -= 8


def _serialize(pages: list[list[str]], title: str) -> bytes:
    total = len(pages)
    for number, ops in enumerate(pages, start=1):
        label = f"Lenscribe  -  {number} / {total}"
        size = 8.0
        width = _measure(label, size)
        ops.append(
            f"q 0.55 0.60 0.65 rg BT /F1 {size:.2f} Tf "
            f"{(PAGE_W - width) / 2:.2f} {BOTTOM / 2:.2f} Td ({_pdf_string(label)}) Tj ET Q"
        )

    page_ids = [8 + 2 * index for index in range(total)]
    objects: dict[int, bytes] = {}

    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objects[2] = f"<< /Type /Pages /Kids [{kids}] /Count {total} >>".encode("ascii")
    objects[3] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
    objects[4] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
    objects[5] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>"
    objects[6] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding /WinAnsiEncoding >>"

    stamp = datetime.now(timezone.utc).strftime("D:%Y%m%d%H%M%SZ")
    objects[7] = (
        f"<< /Title ({_pdf_string(title)}) /Author (Lenscribe) "
        f"/Creator (Lenscribe) /Producer (Lenscribe) /CreationDate ({stamp}) >>"
    ).encode("ascii")

    for index, ops in enumerate(pages):
        content = "\n".join(ops).encode("ascii")
        page_id = page_ids[index]
        content_id = page_id + 1
        objects[page_id] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_W:.2f} {PAGE_H:.2f}] "
            "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R >> >> "
            f"/Contents {content_id} 0 R >>"
        ).encode("ascii")
        objects[content_id] = (
            f"<< /Length {len(content)} >>\nstream\n".encode("ascii")
            + content
            + b"\nendstream"
        )

    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets: dict[int, int] = {}
    for object_id in sorted(objects):
        offsets[object_id] = len(output)
        output += f"{object_id} 0 obj\n".encode("ascii")
        output += objects[object_id]
        output += b"\nendobj\n"

    xref_start = len(output)
    highest = max(objects)
    output += f"xref\n0 {highest + 1}\n".encode("ascii")
    output += b"0000000000 65535 f \n"
    for object_id in range(1, highest + 1):
        offset = offsets.get(object_id)
        if offset is None:
            output += b"0000000000 65535 f \n"
        else:
            output += f"{offset:010d} 00000 n \n".encode("ascii")
    output += (
        f"trailer\n<< /Size {highest + 1} /Root 1 0 R /Info 7 0 R >>\n"
        f"startxref\n{xref_start}\n%%EOF\n"
    ).encode("ascii")
    return bytes(output)


def build_pdf(markdown: str, title: str | None = None) -> bytes:
    """Return a valid PDF document for the supplied Markdown text."""
    writer = _Writer()
    if title:
        _paragraph(writer, title, size=18.0, bold=True, color=_ACCENT, gap=16.0)

    for block in parse_blocks(markdown):
        if block.kind == "heading":
            _heading(writer, block)
        elif block.kind == "paragraph":
            _paragraph(writer, plain_text(block.text))
        elif block.kind == "bullet":
            _paragraph(writer, plain_text(block.text), gap=4.0, bullet="\u2022")
        elif block.kind == "number":
            _paragraph(writer, plain_text(block.text), gap=4.0, bullet=f"{block.number}.")
        elif block.kind == "quote":
            _quote(writer, plain_text(block.text))
        elif block.kind == "code":
            _code(writer, block.text)
        elif block.kind == "table":
            _table(writer, block)
        elif block.kind == "hr":
            _rule(writer)

    return _serialize(writer.pages, title or "Lenscribe transcription")
