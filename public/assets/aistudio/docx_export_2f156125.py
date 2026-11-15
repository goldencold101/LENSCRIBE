"""Minimal Markdown-to-DOCX writer with no third-party dependencies."""

from __future__ import annotations

import io
import re
import zipfile
from datetime import datetime, timezone
from xml.sax.saxutils import escape

_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_XML_DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'

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

_HEADING_STYLES = {
    1: "Heading1",
    2: "Heading2",
    3: "Heading3",
    4: "Heading4",
    5: "Heading5",
    6: "Heading6",
}

_STYLES_XML = f"""{_XML_DECL}
<w:styles xmlns:w="{_W}">
  <w:docDefaults>
    <w:rPrDefault><w:rPr>
      <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/>
      <w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US"/>
    </w:rPr></w:rPrDefault>
    <w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/><w:qFormat/>
  </w:style>
  <w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont">
    <w:name w:val="Default Paragraph Font"/><w:uiPriority w:val="1"/><w:semiHidden/>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Title">
    <w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/>
    <w:pPr><w:spacing w:after="240"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="0B3B2E"/><w:sz w:val="52"/><w:szCs w:val="52"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:spacing w:before="320" w:after="140"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="0F766E"/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:spacing w:before="280" w:after="120"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="0F766E"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading3">
    <w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:spacing w:before="240" w:after="100"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="334155"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading4">
    <w:name w:val="heading 4"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="334155"/><w:sz w:val="23"/><w:szCs w:val="23"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading5">
    <w:name w:val="heading 5"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:spacing w:before="180" w:after="70"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="475569"/><w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading6">
    <w:name w:val="heading 6"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:keepNext/><w:spacing w:before="160" w:after="60"/></w:pPr>
    <w:rPr><w:b/><w:color w:val="475569"/><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Quote">
    <w:name w:val="Quote"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:ind w:left="420"/><w:pBdr><w:left w:val="single" w:sz="12" w:space="8" w:color="A7F3D0"/></w:pBdr></w:pPr>
    <w:rPr><w:i/><w:color w:val="475569"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Code">
    <w:name w:val="Code"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/><w:shd w:val="clear" w:color="auto" w:fill="F5F7FA"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/><w:sz w:val="19"/><w:szCs w:val="19"/></w:rPr>
  </w:style>
  <w:style w:type="character" w:styleId="CodeChar">
    <w:name w:val="Code Char"/><w:basedOn w:val="DefaultParagraphFont"/>
    <w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/><w:color w:val="0F766E"/><w:sz w:val="19"/></w:rPr>
  </w:style>
</w:styles>
"""


def _xml(text: str) -> str:
    cleaned = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
    return escape(cleaned)


def _run(
    text: str,
    *,
    bold: bool = False,
    italic: bool = False,
    code: bool = False,
    strike: bool = False,
) -> str:
    props: list[str] = []
    if code:
        props.append('<w:rStyle w:val="CodeChar"/>')
    if bold:
        props.append("<w:b/>")
    if italic:
        props.append("<w:i/>")
    if strike:
        props.append("<w:strike/>")
    rpr = f"<w:rPr>{''.join(props)}</w:rPr>" if props else ""
    return f'<w:r>{rpr}<w:t xml:space="preserve">{_xml(text)}</w:t></w:r>'


def _runs(text: str, *, bold: bool = False) -> str:
    parts: list[str] = []
    position = 0
    for match in _INLINE_RE.finditer(text):
        if match.start() > position:
            parts.append(_run(text[position : match.start()], bold=bold))
        if match.group("image"):
            parts.append(_run(f"[image: {match.group('alt')}]", bold=bold))
        elif match.group("bold"):
            parts.append(_run(match.group("bt") or match.group("bu") or "", bold=True))
        elif match.group("italic"):
            parts.append(
                _run(match.group("it") or match.group("iu") or "", italic=True, bold=bold)
            )
        elif match.group("code"):
            parts.append(_run(match.group("ct") or "", code=True, bold=bold))
        elif match.group("strike"):
            parts.append(_run(match.group("st") or "", strike=True, bold=bold))
        elif match.group("link"):
            parts.append(_run(match.group("lt") or "", bold=bold))
        position = match.end()
    if position < len(text):
        parts.append(_run(text[position:], bold=bold))
    return "".join(parts)


def _runs_with_breaks(lines: list[str]) -> str:
    parts: list[str] = []
    for index, line in enumerate(lines):
        if index:
            parts.append("<w:r><w:br/></w:r>")
        parts.append(_runs(line))
    return "".join(parts)


def _paragraph(
    runs: str,
    *,
    style: str | None = None,
    indent: int | None = None,
    border_bottom: bool = False,
    spacing_after: int | None = None,
) -> str:
    props: list[str] = []
    if style:
        props.append(f'<w:pStyle w:val="{style}"/>')
    if border_bottom:
        props.append(
            '<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="CBD5E1"/></w:pBdr>'
        )
    if spacing_after is not None:
        props.append(f'<w:spacing w:after="{spacing_after}"/>')
    if indent is not None:
        props.append(f'<w:ind w:left="{indent}"/>')
    ppr = f"<w:pPr>{''.join(props)}</w:pPr>" if props else ""
    return f"<w:p>{ppr}{runs}</w:p>"


def _split_row(line: str) -> list[str]:
    row = line.strip()
    if row.startswith("|"):
        row = row[1:]
    if row.endswith("|"):
        row = row[:-1]
    cells = re.split(r"(?<!\\)\|", row)
    return [cell.strip().replace("\\|", "|") for cell in cells]


def _is_separator(line: str) -> bool:
    if "-" not in line:
        return False
    cells = _split_row(line)
    return bool(cells) and all(_SEPARATOR_CELL_RE.match(cell or "") for cell in cells)


def _is_table_start(lines: list[str], index: int) -> bool:
    return (
        index + 1 < len(lines)
        and "|" in lines[index]
        and "|" in lines[index + 1]
        and _is_separator(lines[index + 1])
    )


def _table(rows: list[list[str]]) -> str:
    columns = max(len(row) for row in rows)
    grid = "".join('<w:gridCol w:w="1800"/>' for _ in range(columns))
    borders = "".join(
        f'<w:{side} w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>'
        for side in ("top", "left", "bottom", "right", "insideH", "insideV")
    )
    trs: list[str] = []
    for row_index, row in enumerate(rows):
        cells: list[str] = []
        for column in range(columns):
            text = row[column] if column < len(row) else ""
            shade = (
                '<w:shd w:val="clear" w:color="auto" w:fill="E8F5F1"/>'
                if row_index == 0
                else ""
            )
            tcpr = f'<w:tcPr><w:tcW w:w="1800" w:type="dxa"/>{shade}</w:tcPr>'
            runs = _runs(text, bold=row_index == 0) if text else ""
            cells.append(f"<w:tc>{tcpr}{_paragraph(runs, spacing_after=0)}</w:tc>")
        trs.append(f"<w:tr>{''.join(cells)}</w:tr>")
    return (
        '<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>'
        f"<w:tblBorders>{borders}</w:tblBorders></w:tblPr>"
        f"<w:tblGrid>{grid}</w:tblGrid>{''.join(trs)}</w:tbl>"
    )


def _render_blocks(markdown: str, title: str | None) -> str:
    lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    body: list[str] = []
    if title:
        body.append(_paragraph(_runs(title), style="Title"))

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
            for code_line in code or [""]:
                body.append(_paragraph(_run(code_line or " ", code=True), style="Code"))
            continue

        heading = _HEADING_RE.match(line)
        if heading:
            level = len(heading.group(1))
            body.append(
                _paragraph(_runs(heading.group(2)), style=_HEADING_STYLES[level])
            )
            index += 1
            continue

        if _is_table_start(lines, index):
            rows = [_split_row(lines[index])]
            index += 2
            while index < len(lines) and lines[index].strip() and "|" in lines[index]:
                rows.append(_split_row(lines[index]))
                index += 1
            body.append(_table(rows))
            body.append("<w:p/>")
            continue

        if _HR_RE.match(line):
            body.append(_paragraph("", border_bottom=True))
            index += 1
            continue

        quote = _QUOTE_RE.match(line)
        if quote:
            quoted: list[str] = [quote.group(1)]
            index += 1
            while index < len(lines):
                nxt = _QUOTE_RE.match(lines[index])
                if not nxt:
                    break
                quoted.append(nxt.group(1))
                index += 1
            body.append(_paragraph(_runs_with_breaks(quoted), style="Quote"))
            continue

        bullet = _BULLET_RE.match(line)
        if bullet:
            body.append(
                _paragraph(_runs("\u2022  " + bullet.group(1)), indent=360, spacing_after=60)
            )
            index += 1
            continue

        number = _NUMBER_RE.match(line)
        if number:
            body.append(
                _paragraph(
                    _runs(f"{number.group(1)}.  {number.group(2)}"),
                    indent=360,
                    spacing_after=60,
                )
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
        body.append(_paragraph(_runs_with_breaks(paragraph)))

    return "".join(body)


def build_docx(markdown: str, title: str | None = None) -> bytes:
    """Return a valid .docx package for the supplied Markdown text."""
    document = (
        f"{_XML_DECL}"
        f'<w:document xmlns:w="{_W}" xmlns:r="{_R}"><w:body>'
        f"{_render_blocks(markdown, title)}"
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
        '<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" '
        'w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
        "</w:body></w:document>"
    )

    content_types = (
        f"{_XML_DECL}"
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
        "</Types>"
    )

    root_rels = (
        f"{_XML_DECL}"
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
        '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>'
        "</Relationships>"
    )

    document_rels = (
        f"{_XML_DECL}"
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
        "</Relationships>"
    )

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    core = (
        f"{_XML_DECL}"
        '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
        f"<dc:title>{_xml(title or 'Lenscribe transcription')}</dc:title>"
        "<dc:creator>Lenscribe</dc:creator><cp:lastModifiedBy>Lenscribe</cp:lastModifiedBy>"
        f'<dcterms:created xsi:type="dcterms:W3CDTF">{stamp}</dcterms:created>'
        f'<dcterms:modified xsi:type="dcterms:W3CDTF">{stamp}</dcterms:modified>'
        "</cp:coreProperties>"
    )

    app = (
        f"{_XML_DECL}"
        '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" '
        'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">'
        "<Application>Lenscribe</Application></Properties>"
    )

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("word/document.xml", document)
        archive.writestr("word/styles.xml", _STYLES_XML)
        archive.writestr("word/_rels/document.xml.rels", document_rels)
        archive.writestr("docProps/core.xml", core)
        archive.writestr("docProps/app.xml", app)
    return buffer.getvalue()
