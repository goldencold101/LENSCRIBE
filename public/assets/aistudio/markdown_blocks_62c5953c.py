"""Shared Markdown block parsing for the export writers.

The writers target the small subset of Markdown that OCR engines emit:
headings, paragraphs, lists, quotes, fenced code, tables, and rules.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

_HEADING_RE = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
_BULLET_RE = re.compile(r"^\s{0,3}[-*+]\s+(.*)$")
_NUMBER_RE = re.compile(r"^\s{0,3}(\d{1,9})[.)]\s+(.*)$")
_QUOTE_RE = re.compile(r"^\s{0,3}>\s?(.*)$")
_HR_RE = re.compile(r"^\s{0,3}([-*_])(?:\s*\1){2,}\s*$")
_SEPARATOR_CELL_RE = re.compile(r"^\s*:?-{3,}:?\s*$")

_INLINE_RE = re.compile(
    r"(?P<image>!\[(?P<alt>[^\]]*)\]\([^)]*\))"
    r"|(?P<bold>\*\*(?P<bt>[^*]+)\*\*|__(?P<bu>[^_]+)__)"
    r"|(?P<italic>\*(?P<it>[^*]+)\*|_(?P<iu>[^_]+)_)"
    r"|(?P<code>`(?P<ct>[^`]+)`)"
    r"|(?P<strike>~~(?P<st>[^~]+)~~)"
    r"|(?P<link>\[(?P<lt>[^\]]+)\]\((?P<lu>[^)]*)\))"
)


@dataclass(slots=True)
class Block:
    kind: str
    text: str = ""
    level: int = 0
    number: int = 0
    rows: list[list[str]] = field(default_factory=list)


def plain_text(text: str) -> str:
    """Strip inline Markdown markup, keeping the visible characters."""
    parts: list[str] = []
    position = 0
    for match in _INLINE_RE.finditer(text):
        if match.start() > position:
            parts.append(text[position : match.start()])
        if match.group("image"):
            parts.append(f"[image: {match.group('alt')}]")
        elif match.group("bold"):
            parts.append(match.group("bt") or match.group("bu") or "")
        elif match.group("italic"):
            parts.append(match.group("it") or match.group("iu") or "")
        elif match.group("code"):
            parts.append(match.group("ct") or "")
        elif match.group("strike"):
            parts.append(match.group("st") or "")
        elif match.group("link"):
            parts.append(match.group("lt") or "")
        position = match.end()
    if position < len(text):
        parts.append(text[position:])
    return "".join(parts)


def split_row(line: str) -> list[str]:
    row = line.strip()
    if row.startswith("|"):
        row = row[1:]
    if row.endswith("|"):
        row = row[:-1]
    return [cell.strip().replace("\\|", "|") for cell in re.split(r"(?<!\\)\|", row)]


def _is_separator(line: str) -> bool:
    if "-" not in line:
        return False
    cells = split_row(line)
    return bool(cells) and all(_SEPARATOR_CELL_RE.match(cell or "") for cell in cells)


def _is_table_start(lines: list[str], index: int) -> bool:
    return (
        index + 1 < len(lines)
        and "|" in lines[index]
        and "|" in lines[index + 1]
        and _is_separator(lines[index + 1])
    )


def parse_blocks(markdown: str) -> list[Block]:
    lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    blocks: list[Block] = []
    index = 0

    while index < len(lines):
        line = lines[index]
        stripped = line.strip()
        if not stripped:
            index += 1
            continue

        if stripped.startswith("```"):
            index += 1
            code: list[str] = []
            while index < len(lines) and not lines[index].strip().startswith("```"):
                code.append(lines[index])
                index += 1
            index += 1
            blocks.append(Block("code", text="\n".join(code)))
            continue

        heading = _HEADING_RE.match(line)
        if heading:
            blocks.append(
                Block("heading", text=heading.group(2), level=len(heading.group(1)))
            )
            index += 1
            continue

        if _is_table_start(lines, index):
            rows = [split_row(lines[index])]
            index += 2
            while index < len(lines) and lines[index].strip() and "|" in lines[index]:
                rows.append(split_row(lines[index]))
                index += 1
            blocks.append(Block("table", rows=rows))
            continue

        if _HR_RE.match(line):
            blocks.append(Block("hr"))
            index += 1
            continue

        quote = _QUOTE_RE.match(line)
        if quote:
            quoted = [quote.group(1)]
            index += 1
            while index < len(lines):
                nxt = _QUOTE_RE.match(lines[index])
                if not nxt:
                    break
                quoted.append(nxt.group(1))
                index += 1
            blocks.append(Block("quote", text="\n".join(quoted)))
            continue

        bullet = _BULLET_RE.match(line)
        if bullet:
            blocks.append(Block("bullet", text=bullet.group(1)))
            index += 1
            continue

        number = _NUMBER_RE.match(line)
        if number:
            blocks.append(
                Block("number", text=number.group(2), number=int(number.group(1)))
            )
            index += 1
            continue

        paragraph = [stripped]
        index += 1
        while index < len(lines):
            candidate = lines[index]
            candidate_text = candidate.strip()
            if (
                not candidate_text
                or _HEADING_RE.match(candidate)
                or candidate_text.startswith("```")
                or _QUOTE_RE.match(candidate)
                or _BULLET_RE.match(candidate)
                or _NUMBER_RE.match(candidate)
                or _HR_RE.match(candidate)
                or _is_table_start(lines, index)
            ):
                break
            paragraph.append(candidate_text)
            index += 1
        blocks.append(Block("paragraph", text="\n".join(paragraph)))

    return blocks


def document_title(markdown: str, fallback: str = "Lenscribe transcription") -> str:
    for block in parse_blocks(markdown):
        if block.kind == "heading" and block.text.strip():
            return plain_text(block.text).strip() or fallback
    return fallback
