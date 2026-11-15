"""Minimal Markdown-to-PPTX writer with no third-party dependencies.

Creates a widescreen deck: one title slide, content slides split at headings,
and one slide per Markdown table chunk. Text boxes use Calibri; no embedded
assets are required.
"""

from __future__ import annotations

import io
import math
import re
import textwrap
import zipfile
from datetime import datetime, timezone
from xml.sax.saxutils import escape

from markdown_blocks import Block, parse_blocks, plain_text

_XML_DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
_NS = f'xmlns:a="{_A}" xmlns:r="{_R}" xmlns:p="{_P}"'

SLIDE_CX = 12192000
SLIDE_CY = 6858000
MARGIN_X = 731520
TITLE_Y = 457200
TITLE_CY = 762000
ACCENT_Y = 1188720
CONTENT_Y = 1371600
CONTENT_CX = SLIDE_CX - 2 * MARGIN_X
CONTENT_CY = SLIDE_CY - CONTENT_Y - 457200

_BODY_SIZE = 1800
_CHARS_PER_LINE = 78
_MAX_LINES = 13
_TABLE_ROWS_PER_SLIDE = 8
_TABLE_ROW_HEIGHT = 457200

_ILLEGAL_XML = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")


def _xml(text: str) -> str:
    return escape(_ILLEGAL_XML.sub("", str(text)))


def _run(
    text: str,
    *,
    size: int = _BODY_SIZE,
    bold: bool = False,
    italic: bool = False,
    color: str = "1E293B",
    mono: bool = False,
) -> str:
    face = "Consolas" if mono else "Calibri"
    attrs = f' lang="en-US" sz="{size}"'
    if bold:
        attrs += ' b="1"'
    if italic:
        attrs += ' i="1"'
    return (
        f"<a:r><a:rPr{attrs} dirty=\"0\">"
        f'<a:solidFill><a:srgbClr val="{color}"/></a:solidFill>'
        f'<a:latin typeface="{face}"/><a:cs typeface="{face}"/></a:rPr>'
        f'<a:t xml:space="preserve">{_xml(text)}</a:t></a:r>'
    )


def _paragraph(
    text: str,
    *,
    size: int = _BODY_SIZE,
    bold: bool = False,
    italic: bool = False,
    color: str = "1E293B",
    mono: bool = False,
    bullet: str | None = None,
    number: int | None = None,
    align: str | None = None,
    space_after: int = 600,
) -> str:
    attrs = ' marL="342900" indent="-342900"' if bullet or number else ' marL="0" indent="0"'
    if align:
        attrs += f' algn="{align}"'
    props = [f"<a:pPr{attrs}>"]
    props.append(f"<a:spcAft><a:spcPts val=\"{space_after}\"/></a:spcAft>")
    if bullet:
        props.append(f'<a:buFont typeface="Arial"/><a:buChar char="{_xml(bullet)}"/>')
    elif number:
        props.append(
            f'<a:buFont typeface="Calibri"/>'
            f'<a:buAutoNum type="arabicPeriod" startAt="{max(1, number)}"/>'
        )
    else:
        props.append("<a:buNone/>")
    props.append("</a:pPr>")
    run = _run(text, size=size, bold=bold, italic=italic, color=color, mono=mono)
    return "<a:p>" + "".join(props) + run + f'<a:endParaRPr lang="en-US" sz="{size}"/></a:p>'


def _text_body(paragraphs: list[str], anchor: str = "t") -> str:
    body = "".join(paragraphs) or "<a:p/>"
    return (
        '<p:txBody><a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" '
        f'anchor="{anchor}"><a:normAutofit/></a:bodyPr><a:lstStyle/>'
        + body
        + "</p:txBody>"
    )


def _text_box(shape_id: int, name: str, x: int, y: int, cx: int, cy: int, paragraphs: list[str]) -> str:
    return (
        f'<p:sp><p:nvSpPr><p:cNvPr id="{shape_id}" name="{name}"/>'
        '<p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm>'
        f'<a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{cy}"/>'
        '</a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>'
        + _text_body(paragraphs)
        + "</p:sp>"
    )


def _accent_bar(shape_id: int) -> str:
    return (
        f'<p:sp><p:nvSpPr><p:cNvPr id="{shape_id}" name="Accent"/>'
        '<p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
        f'<p:spPr><a:xfrm><a:off x="{MARGIN_X}" y="{ACCENT_Y}"/>'
        '<a:ext cx="1097280" cy="36576"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>'
        '<a:solidFill><a:srgbClr val="0F766E"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>'
        '<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>'
    )


def _col_widths(rows: list[list[str]], total: int) -> list[int]:
    columns = max(len(row) for row in rows)
    natural: list[float] = []
    for column in range(columns):
        longest = max(
            (len(plain_text(row[column])) for row in rows if column < len(row)),
            default=1,
        )
        natural.append(max(6.0, min(40.0, longest)))
    scale = total / sum(natural)
    return [max(1, int(width * scale)) for width in natural]


def _table_frame(shape_id: int, rows: list[list[str]], x: int, y: int, cx: int) -> str:
    columns = max(len(row) for row in rows)
    widths = _col_widths(rows, cx)
    grid = "".join(f'<a:gridCol w="{width}"/>' for width in widths)
    row_height = min(_TABLE_ROW_HEIGHT, max(320040, CONTENT_CY // max(1, len(rows))))
    trs: list[str] = []
    for row_index, row in enumerate(rows):
        cells: list[str] = []
        for column in range(columns):
            text = plain_text(row[column]) if column < len(row) else ""
            header = row_index == 0
            fill = (
                '<a:solidFill><a:srgbClr val="D1FAE5"/></a:solidFill>'
                if header
                else '<a:solidFill><a:srgbClr val="F8FAFC"/></a:solidFill>'
                if row_index % 2 == 0
                else ""
            )
            border = (
                '<a:lnL w="6350"><a:solidFill><a:srgbClr val="CBD5E1"/></a:solidFill></a:lnL>'
                '<a:lnR w="6350"><a:solidFill><a:srgbClr val="CBD5E1"/></a:solidFill></a:lnR>'
                '<a:lnT w="6350"><a:solidFill><a:srgbClr val="CBD5E1"/></a:solidFill></a:lnT>'
                '<a:lnB w="6350"><a:solidFill><a:srgbClr val="CBD5E1"/></a:solidFill></a:lnB>'
            )
            paragraph = _paragraph(
                text,
                size=1200 if header else 1100,
                bold=header,
                color="064E3B" if header else "1E293B",
                space_after=0,
            )
            cells.append(
                "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>"
                + paragraph
                + f'</a:txBody><a:tcPr marL="45720" marR="45720" marT="27432" marB="27432" anchor="ctr">'
                + border
                + fill
                + "</a:tcPr></a:tc>"
            )
        trs.append(f'<a:tr h="{row_height}">{"".join(cells)}</a:tr>')

    height = row_height * len(rows)
    return (
        '<p:graphicFrame><p:nvGraphicFramePr>'
        f'<p:cNvPr id="{shape_id}" name="Table {shape_id}"/>'
        '<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>'
        f'<p:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{height}"/></p:xfrm>'
        '<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">'
        '<a:tbl><a:tblPr firstRow="1" bandRow="0">'
        '<a:tableStyleId>{5940675A-B579-460E-94D1-54222C63F5DA}</a:tableStyleId>'
        f"</a:tblPr><a:tblGrid>{grid}</a:tblGrid>{''.join(trs)}</a:tbl>"
        "</a:graphicData></a:graphic></p:graphicFrame>"
    )


def _slide_xml(shapes: list[str]) -> str:
    return (
        f"{_XML_DECL}<p:sld {_NS}><p:cSld><p:spTree>"
        '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>'
        '<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
        + "".join(shapes)
        + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    )


def _title_slide(title: str) -> str:
    title_box = _text_box(
        2,
        "Title",
        MARGIN_X,
        2286000,
        CONTENT_CX,
        1371600,
        [_paragraph(title, size=4000, bold=True, color="0F766E", space_after=0)],
    )
    stamp = datetime.now(timezone.utc).strftime("%d %b %Y")
    subtitle = _text_box(
        3,
        "Subtitle",
        MARGIN_X,
        3840480,
        CONTENT_CX,
        914400,
        [_paragraph("Transcribed with Lenscribe", size=1800, color="475569", space_after=200),
         _paragraph(stamp, size=1400, color="94A3B8")],
    )
    # A rectangle with a fill: reuse the accent bar but at the title slide position.
    bar = (
        '<p:sp><p:nvSpPr><p:cNvPr id="4" name="Accent"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
        f'<p:spPr><a:xfrm><a:off x="{MARGIN_X}" y="2240280"/>'
        '<a:ext cx="1097280" cy="36576"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>'
        '<a:solidFill><a:srgbClr val="0F766E"/></a:solidFill>'
        '<a:ln><a:noFill/></a:ln></p:spPr>'
        '<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>'
    )
    return _slide_xml([title_box, subtitle, bar])


def _content_slide(title: str, blocks: list[Block]) -> str:
    paragraphs = [_paragraph(title, size=2600, bold=True, color="0F766E", space_after=0)]
    for block in blocks:
        if block.kind == "subheading":
            paragraphs.append(
                _paragraph(plain_text(block.text), size=1700, bold=True, color="334155")
            )
        elif block.kind == "bullet":
            paragraphs.append(_paragraph(plain_text(block.text), bullet="\u2022", space_after=300))
        elif block.kind == "number":
            paragraphs.append(
                _paragraph(plain_text(block.text), number=block.number, space_after=300)
            )
        elif block.kind == "quote":
            paragraphs.append(
                _paragraph(
                    plain_text(block.text).replace("\n", " "),
                    italic=True,
                    color="475569",
                )
            )
        elif block.kind == "code":
            for line in block.text.split("\n"):
                paragraphs.append(_paragraph(line or " ", mono=True, size=1400, space_after=0))
        elif block.kind == "hr":
            paragraphs.append(_paragraph(" ", size=800, space_after=0))
        else:
            paragraphs.append(_paragraph(plain_text(block.text).replace("\n", " ")))

    title_box = _text_box(2, "Title", MARGIN_X, TITLE_Y, CONTENT_CX, TITLE_CY, paragraphs[:1])
    content_box = _text_box(
        4, "Content", MARGIN_X, CONTENT_Y, CONTENT_CX, CONTENT_CY, paragraphs[1:]
    )
    return _slide_xml([title_box, _accent_bar(3), content_box])


def _table_slide(title: str, rows: list[list[str]], shape_id: int = 4) -> str:
    title_box = _text_box(
        2,
        "Title",
        MARGIN_X,
        TITLE_Y,
        CONTENT_CX,
        TITLE_CY,
        [_paragraph(title, size=2600, bold=True, color="0F766E", space_after=0)],
    )
    table = _table_frame(shape_id, rows, MARGIN_X, CONTENT_Y, CONTENT_CX)
    return _slide_xml([title_box, _accent_bar(3), table])


def _estimate_lines(block: Block) -> int:
    if block.kind == "code":
        return max(1, len(block.text.split("\n")))
    text = plain_text(block.text)
    return sum(
        max(1, math.ceil(len(part) / _CHARS_PER_LINE)) for part in text.split("\n")
    )


def _split_block(block: Block, max_lines: int) -> list[Block]:
    if block.kind not in {"paragraph", "quote", "code"}:
        return [block]
    width = _CHARS_PER_LINE if block.kind != "code" else 92
    wrapped: list[str] = []
    for part in block.text.split("\n"):
        wrapped.extend(textwrap.wrap(part, width) or [""])
    chunks = ["\n".join(wrapped[index : index + max_lines]) for index in range(0, len(wrapped), max_lines)]
    return [Block(block.kind, text=chunk) for chunk in chunks]


def _content_slides(blocks: list[Block], default_title: str) -> list[dict]:
    slides: list[dict] = []
    current: dict = {"title": default_title, "blocks": []}
    slides.append(current)
    used = 0

    def flush(title: str) -> None:
        nonlocal current, used
        current = {"title": title, "blocks": []}
        slides.append(current)
        used = 0

    for block in blocks:
        if block.kind == "heading" and block.level <= 2:
            flush(plain_text(block.text).strip() or default_title)
            continue

        if block.kind == "table":
            if current["blocks"]:
                flush(current["title"])
            else:
                slides.pop()
            header = block.rows[0] if block.rows else []
            body = block.rows[1:] if len(block.rows) > 1 else []
            if not body:
                slides.append({"title": current["title"], "table": block.rows})
            else:
                for start in range(0, len(body), _TABLE_ROWS_PER_SLIDE):
                    chunk = [header] + body[start : start + _TABLE_ROWS_PER_SLIDE]
                    title = current["title"] if start == 0 else f"{current['title']} (cont.)"
                    slides.append({"title": title, "table": chunk})
            current = {"title": current["title"], "blocks": []}
            slides.append(current)
            used = 0
            continue

        cost = _estimate_lines(block)
        if cost > _MAX_LINES:
            pieces = _split_block(block, _MAX_LINES)
        else:
            pieces = [block]

        for piece in pieces:
            piece_cost = _estimate_lines(piece)
            if current["blocks"] and used + piece_cost > _MAX_LINES:
                flush(current["title"])
            current["blocks"].append(piece)
            used += piece_cost

    return slides


def _filtered_slides(specs: list[dict]) -> list[dict]:
    return [
        spec for spec in specs if spec.get("table") or spec.get("blocks")
    ]


def _content_types(slide_count: int) -> str:
    return (
        f"{_XML_DECL}"
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
        '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
        '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
        + "".join(
            f'<Override PartName="/ppt/slides/slide{index}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
            for index in range(1, slide_count + 1)
        )
        + '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
        '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>'
        '<Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>'
        '<Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
        "</Types>"
    )


_THEME = f"""{_XML_DECL}
<a:theme xmlns:a="{_A}" name="Lenscribe">
  <a:themeElements>
    <a:clrScheme name="Lenscribe">
      <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
      <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="0F172A"/></a:dk2>
      <a:lt2><a:srgbClr val="F1F5F9"/></a:lt2>
      <a:accent1><a:srgbClr val="0F766E"/></a:accent1>
      <a:accent2><a:srgbClr val="14B8A6"/></a:accent2>
      <a:accent3><a:srgbClr val="84CC16"/></a:accent3>
      <a:accent4><a:srgbClr val="06B6D4"/></a:accent4>
      <a:accent5><a:srgbClr val="6366F1"/></a:accent5>
      <a:accent6><a:srgbClr val="F59E0B"/></a:accent6>
      <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
      <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Lenscribe">
      <a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
      <a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
    </a:fontScheme>
    <a:fmtScheme name="Lenscribe">
      <a:fillStyleLst>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"><a:tint val="50000"/></a:schemeClr></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill>
      </a:fillStyleLst>
      <a:lnStyleLst>
        <a:ln w="6350" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
        <a:ln w="12700" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
        <a:ln w="19050" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
      </a:lnStyleLst>
      <a:effectStyleLst>
        <a:effectStyle><a:effectLst/></a:effectStyle>
        <a:effectStyle><a:effectLst/></a:effectStyle>
        <a:effectStyle><a:effectLst/></a:effectStyle>
      </a:effectStyleLst>
      <a:bgFillStyleLst>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"><a:tint val="95000"/></a:schemeClr></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"><a:shade val="90000"/></a:schemeClr></a:solidFill>
      </a:bgFillStyleLst>
    </a:fmtScheme>
  </a:themeElements>
  <a:objectDefaults/><a:extraClrSchemeLst/>
</a:theme>
"""

_SLIDE_MASTER = f"""{_XML_DECL}
<p:sldMaster {_NS}>
  <p:cSld>
    <p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>
        <a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
    </p:spTree>
  </p:cSld>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1"
    accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5"
    accent6="accent6" hlink="hlink" folHlink="folHlink"/>
  <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
  <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>
"""

_SLIDE_LAYOUT = f"""{_XML_DECL}
<p:sldLayout {_NS} type="blank" preserve="1">
  <p:cSld name="Blank">
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>
        <a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>
"""


def build_pptx(markdown: str, title: str | None = None) -> bytes:
    """Return a valid .pptx deck for the supplied Markdown text."""
    blocks = parse_blocks(markdown)
    heading = next(
        (plain_text(block.text).strip() for block in blocks if block.kind == "heading" and block.text.strip()),
        None,
    )
    deck_title = (title or heading or "Lenscribe transcription").strip()

    specs: list[dict] = [{"title": deck_title, "title_slide": True}]
    specs.extend(_filtered_slides(_content_slides(blocks, deck_title)))
    if len(specs) == 1:
        specs.append({"title": deck_title, "blocks": [Block("paragraph", text=markdown.strip())]})

    slide_xml: list[str] = []
    for spec in specs:
        if spec.get("title_slide"):
            slide_xml.append(_title_slide(spec["title"]))
        elif spec.get("table"):
            slide_xml.append(_table_slide(spec["title"], spec["table"]))
        else:
            slide_xml.append(_content_slide(spec["title"], spec.get("blocks") or []))

    slide_count = len(slide_xml)
    presentation = (
        f"{_XML_DECL}<p:presentation {_NS} saveSubsetFonts=\"1\">"
        '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
        "<p:sldIdLst>"
        + "".join(
            f'<p:sldId id="{255 + index}" r:id="rId{index + 1}"/>'
            for index in range(1, slide_count + 1)
        )
        + "</p:sldIdLst>"
        f'<p:sldSz cx="{SLIDE_CX}" cy="{SLIDE_CY}"/>'
        '<p:notesSz cx="6858000" cy="9144000"/>'
        "</p:presentation>"
    )

    presentation_rels = (
        f"{_XML_DECL}"
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>'
        + "".join(
            f'<Relationship Id="rId{index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide{index}.xml"/>'
            for index in range(1, slide_count + 1)
        )
        + f'<Relationship Id="rId{slide_count + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps" Target="presProps.xml"/>'
        f'<Relationship Id="rId{slide_count + 3}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/viewProps" Target="viewProps.xml"/>'
        f'<Relationship Id="rId{slide_count + 4}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/tableStyles" Target="tableStyles.xml"/>'
        "</Relationships>"
    )

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    core = (
        f"{_XML_DECL}"
        '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
        f"<dc:title>{_xml(deck_title)}</dc:title>"
        "<dc:creator>Lenscribe</dc:creator><cp:lastModifiedBy>Lenscribe</cp:lastModifiedBy>"
        f'<dcterms:created xsi:type="dcterms:W3CDTF">{stamp}</dcterms:created>'
        f'<dcterms:modified xsi:type="dcterms:W3CDTF">{stamp}</dcterms:modified>'
        "</cp:coreProperties>"
    )
    app = (
        f"{_XML_DECL}"
        '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" '
        'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">'
        "<Application>Lenscribe</Application><PresentationFormat>Widescreen</PresentationFormat>"
        f"<Slides>{slide_count}</Slides></Properties>"
    )

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", _content_types(slide_count))
        archive.writestr(
            "_rels/.rels",
            f"{_XML_DECL}"
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
            '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>'
            "</Relationships>",
        )
        archive.writestr("ppt/presentation.xml", presentation)
        archive.writestr("ppt/_rels/presentation.xml.rels", presentation_rels)
        archive.writestr(
            "ppt/presProps.xml",
            f'{_XML_DECL}<p:presentationPr {_NS}/>',
        )
        archive.writestr(
            "ppt/viewProps.xml",
            f'{_XML_DECL}<p:viewPr {_NS}/>',
        )
        archive.writestr(
            "ppt/tableStyles.xml",
            f'{_XML_DECL}<a:tblStyleLst xmlns:a="{_A}" def="{{5940675A-B579-460E-94D1-54222C63F5DA}}"/>',
        )
        archive.writestr("ppt/theme/theme1.xml", _THEME)
        archive.writestr("ppt/slideMasters/slideMaster1.xml", _SLIDE_MASTER)
        archive.writestr(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            f"{_XML_DECL}"
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>'
            "</Relationships>",
        )
        archive.writestr("ppt/slideLayouts/slideLayout1.xml", _SLIDE_LAYOUT)
        archive.writestr(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            f"{_XML_DECL}"
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>'
            "</Relationships>",
        )
        for index, xml in enumerate(slide_xml, start=1):
            archive.writestr(f"ppt/slides/slide{index}.xml", xml)
            archive.writestr(
                f"ppt/slides/_rels/slide{index}.xml.rels",
                f"{_XML_DECL}"
                '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
                "</Relationships>",
            )
        archive.writestr("docProps/core.xml", core)
        archive.writestr("docProps/app.xml", app)
    return buffer.getvalue()
