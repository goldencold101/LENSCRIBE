"""Minimal Markdown-to-XLSX writer with no third-party dependencies.

The transcript becomes a wrapped-text sheet, and every Markdown table gets its
own sheet with a styled header row and cell borders.
"""

from __future__ import annotations

import io
import re
import zipfile
from datetime import datetime, timezone
from xml.sax.saxutils import escape

from markdown_blocks import parse_blocks, plain_text

_XML_DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
_MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

_STYLES = f"""{_XML_DECL}
<styleSheet xmlns="{_MAIN_NS}">
  <fonts count="5">
    <font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="16"/><color rgb="FF0F766E"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="12"/><color rgb="FF0F172A"/><name val="Calibri"/><family val="2"/></font>
    <font><sz val="10"/><color rgb="FF334155"/><name val="Consolas"/><family val="3"/></font>
    <font><b/><sz val="11"/><color rgb="FF064E3B"/><name val="Calibri"/><family val="2"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD1FAE5"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF8FAFC"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="7">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="4" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
  <dxfs count="0"/>
  <tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
</styleSheet>
"""

_ILLEGAL_XML = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")


def _xml(text: str) -> str:
    return escape(_ILLEGAL_XML.sub("", str(text)))


def _column(index: int) -> str:
    letters = ""
    index += 1
    while index:
        index, remainder = divmod(index - 1, 26)
        letters = chr(65 + remainder) + letters
    return letters


def _cell(reference: str, text: str, style: int) -> str:
    value = _xml(text[:32767])
    return (
        f'<c r="{reference}" t="inlineStr" s="{style}">'
        f'<is><t xml:space="preserve">{value}</t></is></c>'
    )


def _sheet_xml(
    rows: list[list[tuple[str, int]]],
    widths: list[float],
    *,
    freeze_header: bool = False,
) -> str:
    row_xml: list[str] = []
    for row_index, cells in enumerate(rows, start=1):
        if not cells:
            row_xml.append(f'<row r="{row_index}"/>')
            continue
        rendered = "".join(
            _cell(f"{_column(column)}{row_index}", text, style)
            for column, (text, style) in enumerate(cells)
        )
        row_xml.append(f'<row r="{row_index}">{rendered}</row>')

    cols = "".join(
        f'<col min="{index + 1}" max="{index + 1}" width="{width:.1f}" customWidth="1"/>'
        for index, width in enumerate(widths)
    )
    pane = (
        '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
        if freeze_header
        else ""
    )
    last_column = _column(max(0, len(widths) - 1))
    dimension = f"A1:{last_column}{max(1, len(rows))}"
    return (
        f"{_XML_DECL}"
        f'<worksheet xmlns="{_MAIN_NS}" xmlns:r="{_REL_NS}">'
        f'<dimension ref="{dimension}"/>'
        f'<sheetViews><sheetView workbookViewId="0">{pane}</sheetView></sheetViews>'
        '<sheetFormatPr defaultRowHeight="15"/>'
        f"<cols>{cols}</cols>"
        f"<sheetData>{''.join(row_xml)}</sheetData>"
        '<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>'
        "</worksheet>"
    )


def _widths(rows: list[list[str]], minimum: float = 10.0, maximum: float = 55.0) -> list[float]:
    columns = max((len(row) for row in rows), default=1)
    widths: list[float] = []
    for column in range(columns):
        longest = max(
            (len(plain_text(row[column])) for row in rows if column < len(row)),
            default=0,
        )
        widths.append(min(maximum, max(minimum, longest * 1.05 + 2)))
    return widths or [minimum]


def build_xlsx(markdown: str, title: str | None = None) -> bytes:
    """Return a valid .xlsx workbook for the supplied Markdown text."""
    transcript: list[list[tuple[str, int]]] = []
    tables: list[list[list[str]]] = []

    if title:
        transcript.append([(title, 1)])

    for block in parse_blocks(markdown):
        if block.kind == "heading":
            text = plain_text(block.text).strip()
            if not text:
                continue
            transcript.append([(text, 2)])
        elif block.kind == "paragraph":
            lines = [line.strip() for line in plain_text(block.text).split("\n") if line.strip()]
            if lines:
                transcript.append([(" ".join(lines), 6)])
        elif block.kind == "quote":
            transcript.append([("> " + plain_text(block.text).replace("\n", " "), 6)])
        elif block.kind == "bullet":
            transcript.append([("\u2022  " + plain_text(block.text), 6)])
        elif block.kind == "number":
            transcript.append([(f"{block.number}.  {plain_text(block.text)}", 6)])
        elif block.kind == "code":
            for line in block.text.split("\n"):
                transcript.append([(line, 3)])
        elif block.kind == "table":
            if block.rows:
                tables.append(block.rows)
        elif block.kind == "hr":
            transcript.append([])

    sheets: list[tuple[str, str, list[float]]] = []
    sheets.append(
        (
            "Transcript",
            _sheet_xml(transcript or [[("", 6)]], [110.0]),
            [110.0],
        )
    )

    for index, rows in enumerate(tables, start=1):
        columns = max(len(row) for row in rows)
        styled: list[list[tuple[str, int]]] = []
        for row_index, row in enumerate(rows):
            cells = [
                (plain_text(row[column]) if column < len(row) else "", 4 if row_index == 0 else 5)
                for column in range(columns)
            ]
            styled.append(cells)
        widths = _widths(rows)
        sheets.append(
            (
                f"Table {index}",
                _sheet_xml(styled, widths, freeze_header=True),
                widths,
            )
        )

    content_types = (
        f"{_XML_DECL}"
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
        + "".join(
            f'<Override PartName="/xl/worksheets/sheet{index}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            for index in range(1, len(sheets) + 1)
        )
        + '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
        "</Types>"
    )

    root_rels = (
        f"{_XML_DECL}"
        f'<Relationships xmlns="{_PKG_REL_NS}">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
        '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>'
        "</Relationships>"
    )

    workbook_sheets = "".join(
        f'<sheet name="{_xml(name)}" sheetId="{index}" r:id="rId{index}"/>'
        for index, (name, _, _) in enumerate(sheets, start=1)
    )
    workbook = (
        f"{_XML_DECL}"
        f'<workbook xmlns="{_MAIN_NS}" xmlns:r="{_REL_NS}">'
        '<bookViews><workbookView activeTab="0"/></bookViews>'
        f"<sheets>{workbook_sheets}</sheets>"
        "</workbook>"
    )

    workbook_rels = (
        f"{_XML_DECL}"
        f'<Relationships xmlns="{_PKG_REL_NS}">'
        + "".join(
            f'<Relationship Id="rId{index}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{index}.xml"/>'
            for index in range(1, len(sheets) + 1)
        )
        + f'<Relationship Id="rId{len(sheets) + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
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
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", workbook_rels)
        archive.writestr("xl/styles.xml", _STYLES)
        for index, (_, sheet, _) in enumerate(sheets, start=1):
            archive.writestr(f"xl/worksheets/sheet{index}.xml", sheet)
        archive.writestr("docProps/core.xml", core)
        archive.writestr("docProps/app.xml", app)
    return buffer.getvalue()
