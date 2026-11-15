"""Plain-text and CSV export writers."""

from __future__ import annotations

import csv
import io

from markdown_blocks import parse_blocks, plain_text


def _table_lines(rows: list[list[str]]) -> list[str]:
    if not rows:
        return []
    columns = max(len(row) for row in rows)
    grid = [
        [plain_text(row[column]) if column < len(row) else "" for column in range(columns)]
        for row in rows
    ]
    widths = [max(len(row[column]) for row in grid) for column in range(columns)]
    lines = [
        " | ".join(cell.ljust(widths[column]) for column, cell in enumerate(row)).rstrip()
        for row in grid
    ]
    separator = "-+-".join("-" * width for width in widths)
    return [lines[0], separator] + lines[1:] if len(lines) > 1 else lines


def build_txt(markdown: str, title: str | None = None) -> bytes:
    """Return a readable plain-text rendition of the transcription."""
    sections: list[str] = []
    for block in parse_blocks(markdown):
        if block.kind == "heading":
            sections.append(plain_text(block.text).strip())
        elif block.kind == "paragraph":
            sections.append(plain_text(block.text))
        elif block.kind == "quote":
            sections.append("> " + plain_text(block.text).replace("\n", "\n> "))
        elif block.kind == "bullet":
            sections.append("- " + plain_text(block.text))
        elif block.kind == "number":
            sections.append(f"{block.number}. {plain_text(block.text)}")
        elif block.kind == "code":
            sections.append("\n".join("    " + line for line in block.text.split("\n")))
        elif block.kind == "table":
            sections.append("\n".join(_table_lines(block.rows)))
        elif block.kind == "hr":
            sections.append("-" * 48)

    text = "\n\n".join(section for section in sections if section.strip())
    return (text + "\n").encode("utf-8")


def build_csv(markdown: str, title: str | None = None) -> bytes:
    """Return CSV rows; Markdown tables keep their columns."""
    blocks = parse_blocks(markdown)
    tables = [block for block in blocks if block.kind == "table"]

    rows: list[list[str]] = []
    if tables:
        for index, table in enumerate(tables):
            if index:
                rows.append([])
            rows.extend(
                [plain_text(row[column]) if column < len(row) else "" for column in range(len(row))]
                for row in table.rows
            )
    else:
        for block in blocks:
            if block.kind == "heading":
                rows.append([plain_text(block.text).strip()])
            elif block.kind == "paragraph":
                rows.append([plain_text(block.text).replace("\n", " ")])
            elif block.kind == "quote":
                rows.append(["> " + plain_text(block.text).replace("\n", " ")])
            elif block.kind == "bullet":
                rows.append(["\u2022 " + plain_text(block.text)])
            elif block.kind == "number":
                rows.append([f"{block.number}. {plain_text(block.text)}"])
            elif block.kind == "code":
                rows.extend([[line] for line in block.text.split("\n")])
            elif block.kind == "hr":
                rows.append([""])

    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\r\n")
    writer.writerows(rows)
    return buffer.getvalue().encode("utf-8-sig")
