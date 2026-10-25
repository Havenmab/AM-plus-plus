#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
amll_to_apple_ttml.py -- convert AMLL TTML into Apple Music TTML.

Standalone Python port of the AM++ module converter
(core/src/main/kotlin/dev/amenhancer/module/lyrics/AmllTtmlFormatConverter.kt).
It rewrites the AMLL variant of TTML (translations / romanizations inline in
every lyric line, empty ``xmlns=""`` overrides) into the Apple Music format
(auxiliary tracks declared once in the head and linked back through
``itunes:key``).

It also sorts lyric lines and auxiliary entries by numeric begin time and
combines same-start translations into the first entry using newlines, retaining
the other entries as space placeholders. Lyric voices and romanization remain
separate, and interleaving sections retain their original ownership.

The rewrite is textual on purpose: whitespace between ``<span>`` tags carries
word separation and a DOM round-trip would not preserve it. Only markup is
examined; lyric text nodes are copied verbatim.

Two shapes AMLL's axis tool left behind are normalized first and idempotently: a
``<p>`` whose only word is bare text gets that word wrapped in a span carrying
the line's own timing, and every ``x-bg`` wrapper is put in the canonical shape
— role on the outer span with no timing there, the text and timing on an inner
span.

Usage:
    python amll_to_apple_ttml.py input.ttml [-o output.ttml] [--validate]
    python amll_to_apple_ttml.py --adam 1158609134 [-o output.ttml]
    python amll_to_apple_ttml.py --selftest

``--adam`` downloads the file from the amll-dev/amll-ttml-db repository the
module uses. ``-`` reads stdin. Without ``-o`` the result goes to stdout; the
``converted:`` line is written to stderr so it never pollutes the TTML.

Python 3.8+ only, no third-party dependencies.
"""

from __future__ import annotations

import argparse
import dataclasses
from decimal import Decimal, localcontext
import re
import sys
import textwrap
import urllib.request
from typing import Callable

TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
TTML_METADATA_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
ROLE_TRANSLATION = "x-translation"
ROLE_ROMAN = "x-roman"

ROLE_BACKGROUND = "x-bg"

# The converted background span carries its own namespace declarations, so the
# role prefix still resolves if the span is moved into a lane body.
BG_SPAN_OPEN = (
    f'<span xmlns="{TTML_NAMESPACE}" xmlns:ttm="{TTML_METADATA_NAMESPACE}" ttm:role="{ROLE_BACKGROUND}">'
)

# Translations keep Apple's subtitle language; the root lyrics language is
# resolved per song (see ``lyrics_language``), never pinned.
TRANSLATION_LANGUAGE = "zh-Hans"

# The <transliteration> tag kept when no lyrics language can be determined.
FALLBACK_PRONUNCIATION_LANGUAGE = "und-Latn"

# How Apple labels a translation meant to be shown under the lyric.
TRANSLATION_TYPE = "subtitle"

# Stands in for a line the track has no text for, keeping the entry there.
ABSENT_TEXT = " "

AUXILIARY_ROLES = {ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND}

AMLL_TTML_DB_BASE = (
    "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main"
)


def _attribute_pattern(name: str) -> re.Pattern:
    return re.compile(
        r"\s" + re.escape(name) + r'\s*=\s*"([^"]*)"|\s' + re.escape(name)
        + r"\s*=\s*'([^']*)'"
    )


EMPTY_DEFAULT_NAMESPACE = re.compile(r"""\s+xmlns\s*=\s*(?:""|'')""")
ROLE_ATTRIBUTE = _attribute_pattern("ttm:role")
LANGUAGE_ATTRIBUTE = _attribute_pattern("xml:lang")
KEY_ATTRIBUTE = _attribute_pattern("itunes:key")
FOR_ATTRIBUTE = _attribute_pattern("for")
BEGIN_ATTRIBUTE_VALUE = _attribute_pattern("begin")
END_ATTRIBUTE_VALUE = _attribute_pattern("end")
TIMING_ATTRIBUTE_VALUE = _attribute_pattern("itunes:timing")
ITUNES_DECLARATION = _attribute_pattern("xmlns:itunes")
SPAN_TIMING = re.compile(
    r"""\s(?:begin|end)\s*=\s*"[^"]*"|\s(?:begin|end)\s*=\s*'[^']*'"""
)
PARENTHESIZED = re.compile(r"^\s*[(（].*[)）]\s*$", re.DOTALL)
TIMELINE_OFFSET = re.compile(r"([0-9]+(?:\.[0-9]+)?)(ms|s|m|h)")
TIMELINE_NUMBER = re.compile(r"[0-9]+(?:\.[0-9]+)?")


@dataclasses.dataclass
class TtmlFormatConversion:
    ttml: str
    converted: bool


@dataclasses.dataclass
class Edit:
    start: int
    end: int
    replacement: str


@dataclasses.dataclass
class TrackEntry:
    key: str
    main: str | None
    background: str | None

    @property
    def has_text(self) -> bool:
        return self.main is not None or self.background is not None


@dataclasses.dataclass
class DeclaredTracks:
    translations: bool
    transliterations: bool

    @property
    def any(self) -> bool:
        return self.translations or self.transliterations


@dataclasses.dataclass
class Element:
    attributes: str
    start: int
    content_start: int
    content_end: int
    end: int
    self_closing: bool


@dataclasses.dataclass(eq=False)
class TimelineSection:
    element: Element
    prefix: str
    tail: str = ""


@dataclasses.dataclass
class TimelineLine:
    element: Element
    begin: Decimal
    ancestors: list[TimelineSection]
    prefix: str


@dataclasses.dataclass
class TrackFormatting:
    edit: Edit | None


# --- Minimal XML element scanning -------------------------------------


def literal_end(text: str, open_: int) -> int:
    """Exclusive end of a comment, CDATA section or processing instruction."""
    if text.startswith("<!--", open_):
        terminator = "-->"
    elif text.startswith("<![CDATA[", open_):
        terminator = "]]>"
    elif text.startswith("<?", open_):
        terminator = "?>"
    else:
        return -1
    close = text.find(terminator, open_)
    return len(text) if close < 0 else close + len(terminator)


def tag_end(text: str, open_: int) -> int:
    """Index of the ``>`` closing the tag, ignoring ``>`` in attribute values."""
    quote = None
    index = open_ + 1
    while index < len(text):
        character = text[index]
        if quote is not None:
            if character == quote:
                quote = None
        elif character in ('"', "'"):
            quote = character
        elif character == ">":
            return index
        index += 1
    return -1


def is_element(text: str, open_: int, name: str) -> bool:
    if text[open_ + 1 : open_ + 1 + len(name)].lower() != name.lower():
        return False
    nxt = text[open_ + 1 + len(name)] if open_ + 1 + len(name) < len(text) else None
    return nxt is not None and (nxt == ">" or nxt == "/" or nxt.isspace())


def is_close_tag(text: str, open_: int, name: str) -> bool:
    if not text.startswith("</", open_):
        return False
    if text[open_ + 2 : open_ + 2 + len(name)].lower() != name.lower():
        return False
    nxt = text[open_ + 2 + len(name)] if open_ + 2 + len(name) < len(text) else None
    return nxt is not None and (nxt == ">" or nxt.isspace())


def element_name(text: str, open_: int, tag_end_: int) -> str | None:
    if text.startswith("</", open_):
        return None
    index = open_ + 1
    while index < tag_end_:
        character = text[index]
        if character.isspace() or character in ("/", ">"):
            break
        index += 1
    name = text[open_ + 1 : index]
    return name if name else None


def parse_element(text: str, name: str, open_: int, tag_end_: int) -> Element | None:
    self_closing = text[tag_end_ - 1] == "/"
    attributes_end = tag_end_ - 1 if self_closing else tag_end_
    attributes = text[open_ + 1 + len(name) : attributes_end]
    if self_closing:
        return Element(attributes, open_, tag_end_ + 1, tag_end_ + 1, tag_end_ + 1, True)
    depth = 1
    index = tag_end_ + 1
    while index < len(text):
        open2 = text.find("<", index)
        if open2 < 0:
            return None
        literal = literal_end(text, open2)
        if literal > 0:
            index = literal
            continue
        end2 = tag_end(text, open2)
        if end2 < 0:
            return None
        if is_close_tag(text, open2, name):
            depth -= 1
            if depth == 0:
                return Element(attributes, open_, tag_end_ + 1, open2, end2 + 1, False)
        elif is_element(text, open2, name) and text[end2 - 1] != "/":
            depth += 1
        index = end2 + 1
    return None


def next_element(text: str, name: str, from_: int) -> Element | None:
    index = from_
    while index < len(text):
        open_ = text.find("<", index)
        if open_ < 0:
            return None
        literal = literal_end(text, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(text, open_)
        if end < 0:
            return None
        if is_element(text, open_, name):
            return parse_element(text, name, open_, end)
        index = end + 1
    return None


def child_elements(text: str, parent: Element) -> list[Element]:
    children: list[Element] = []
    index = parent.content_start
    while index < parent.content_end:
        open_ = text.find("<", index)
        if open_ < 0 or open_ >= parent.content_end:
            break
        literal = literal_end(text, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(text, open_)
        if end < 0:
            break
        name = element_name(text, open_, end)
        child = parse_element(text, name, open_, end) if name else None
        if child is None:
            index = end + 1
            continue
        children.append(child)
        index = child.end
    return children


def attribute(attributes: str, pattern: re.Pattern) -> str | None:
    match = pattern.search(attributes)
    if match is None:
        return None
    return match.group(1) if match.group(1) is not None else match.group(2)


def clock_seconds(value: str | None) -> float | None:
    """Seconds in ``3:02.550`` or AMLL's offset form ``1.5s``; None when unreadable."""
    if value is None:
        return None
    clock = value if ":" in value else (value[:-1] if value.endswith("s") else value)
    parts = clock.split(":")
    if not 1 <= len(parts) <= 3:
        return None
    seconds = 0.0
    for part in parts:
        try:
            seconds = seconds * 60 + float(part)
        except ValueError:
            return None
    return seconds


# --- Track migration --------------------------------------------------


def quote_raw_attribute_value(value: str) -> str:
    """Source attribute content already carries entities; choose a safe delimiter."""
    delimiter = "'" if '"' in value else '"'
    return delimiter + value + delimiter


def to_apple_format(ttml: str, lyric_language: str | None = None) -> TtmlFormatConversion:
    if not ttml:
        return TtmlFormatConversion(ttml, False)
    # AMLL's axis tool leaves two non-canonical shapes behind (see
    # normalize_legacy_shapes). They are fixed first, so every later pass works
    # from one set of element indices.
    normalized = normalize_legacy_shapes(ttml)
    # Judged per kind, so a head that already declares one track does not
    # hide the other kind still sitting inline. An <iTunesMetadata> on its
    # own is no such signal: AMLL writes one to carry <songwriters>.
    declared = declared_tracks(normalized)
    # A declared <transliterations> block whose entries name no language is
    # still a rewrite: the native parser registers a pronunciation lane only
    # under the language its <transliteration> element carries.
    unnamed = unnamed_transliterations(normalized)
    if declared.any and not unnamed and not has_undeclared_inline_track(normalized, declared):
        # The head already speaks for every kind the body carries, so this is
        # Apple's own document and nothing, the root included, is rewritten —
        # but a legacy shape normalized above is still a conversion, and its
        # timeline is formatted like any other document's.
        formatted = format_timeline(normalized)
        return TtmlFormatConversion(formatted, formatted != ttml)
    written_language = transliteration_language(normalized, lyric_language)
    root_language = lyrics_language(normalized, lyric_language)
    migrated = migrate_auxiliary_tracks(normalized, declared, written_language, unnamed)
    # Migrating a line's only auxiliary span away can leave its lyric bare where
    # the source hid it beside that span, so the body's lines are wrapped once
    # more. This pass is deliberately body-only: a head lane is a
    # ``<text for=…>`` entry, not a body ``<p>``, and must never be given a span
    # — the app reads its translation text verbatim.
    canonical = wrap_exposed_bare_lines(migrated)
    body = next_element(canonical, "body", 0)
    result = normalize_tags(
        canonical,
        word_timed=body is not None and has_timed_span(canonical, body),
        language=root_language,
    )
    result = format_timeline(result)
    return TtmlFormatConversion(result, result != ttml)


def normalize_legacy_shapes(ttml: str) -> str:
    """Normalize the shapes AMLL's axis tool left behind.

    - a ``<p>`` whose lyric is bare text gets one inner ``<span>`` carrying the
      line's own ``begin`` / ``end``, so the single word it holds is timed;
    - every ``x-bg`` wrapper is put in the canonical shape: the role on the
      outer span with no timing there, the text and timing on an inner span. A
      single-layer group gains the inner span it is missing; an already
      two-layer group only loses the redundant timing from its wrapper.

    The synthesized inner span deliberately takes the parent's ``begin`` /
    ``end``: the per-word matching that consumes the document aligns a lane by
    span start and end, so the line's only word must carry the line's own
    extent. Only the outer ``x-bg`` wrapper is touched — a plain word or line
    span keeps its timing. Every rule fires only on a shape that is not yet
    canonical, so a second pass is a no-op. A background group with neither an
    inner element to lean on nor text and timing to move is left as it is. The
    ``x-bg`` rule covers the whole document, so a legacy ``x-bg`` inside a
    declared head lane is normalized too; the line rule is confined to the body,
    and a ``<text for=…>`` head entry is never wrapped.
    """
    edits: list[Edit] = []
    wrap_bare_lines(ttml, edits)
    normalize_background_spans(ttml, edits)
    return apply_edits(ttml, edits)


def wrap_exposed_bare_lines(ttml: str) -> str:
    """Wrap the bare-text **body** lines migration exposed, and nothing else.

    Only the body is scanned: the head lanes this runs after are already
    canonical, and a ``<translations>`` / ``<transliterations>`` entry is a
    ``<text for=…>``, not a body ``<p>``. Wrapping one would hand the app a span
    where it expects the translation text itself, which is precisely the failure
    mode this pass must not have.
    """
    edits: list[Edit] = []
    wrap_bare_lines(ttml, edits)
    return apply_edits(ttml, edits)


def wrap_bare_lines(ttml: str, edits: list[Edit]) -> None:
    """Give a bare-text ``<p>`` in the **body** the timed span it is missing.

    The head is out of scope: no head lane holds a ``<p>``, and none may be
    wrapped even if a document carried one there.
    """
    body = next_element(ttml, "body", 0)
    if body is None:
        return
    cursor = body.content_start
    while True:
        line = next_element(ttml, "p", cursor)
        if line is None or line.end > body.content_end:
            break
        cursor = line.end
        if line.self_closing or child_elements(ttml, line):
            continue
        text = ttml[line.content_start : line.content_end]
        if not text.strip():
            continue
        begin = attribute(line.attributes, BEGIN_ATTRIBUTE_VALUE)
        end = attribute(line.attributes, END_ATTRIBUTE_VALUE)
        if begin is None and end is None:
            continue
        edits.append(
            Edit(
                line.content_start,
                line.content_end,
                f"<span{timing_attributes(begin, end)}>{text}</span>",
            )
        )


def normalize_background_spans(ttml: str, edits: list[Edit]) -> None:
    """Put every ``x-bg`` wrapper in the canonical role-only, untimed shape."""
    for span in elements_named(ttml, "span"):
        if span.self_closing:
            continue
        if attribute(span.attributes, ROLE_ATTRIBUTE) != ROLE_BACKGROUND:
            continue
        begin = attribute(span.attributes, BEGIN_ATTRIBUTE_VALUE)
        end = attribute(span.attributes, END_ATTRIBUTE_VALUE)
        open_tag = ttml[span.start : span.content_start]
        if child_elements(ttml, span):
            # Inner spans already time the group, so the wrapper's own copy is
            # redundant; drop only it and keep every other attribute.
            if begin is None and end is None:
                continue
            edits.append(Edit(span.start, span.content_start, SPAN_TIMING.sub("", open_tag)))
            continue
        # A wrapper with no inner span keeps its timing as the fallback when
        # there is nothing to move into one.
        text = ttml[span.content_start : span.content_end]
        if not text.strip():
            continue
        if begin is None and end is None:
            continue
        edits.append(
            Edit(
                span.start,
                span.end,
                f"{SPAN_TIMING.sub('', open_tag)}"
                f"<span{timing_attributes(begin, end)}>{text}</span></span>",
            )
        )


def timing_attributes(begin: str | None, end: str | None) -> str:
    """The ``begin`` / ``end`` attributes for a synthesized span, in order."""
    attributes = ""
    if begin is not None:
        attributes += f' begin="{begin}"'
    if end is not None:
        attributes += f' end="{end}"'
    return attributes


def elements_named(text: str, name: str) -> list[Element]:
    """Every ``name`` element in ``text``, in document order, nested included."""
    elements: list[Element] = []
    index = 0
    while True:
        element = next_element(text, name, index)
        if element is None:
            return elements
        elements.append(element)
        index = element.end if element.self_closing else element.content_start


def timeline_time(value: str | None) -> Decimal | None:
    """Exact decimal comparison, including equivalent clock/offset forms."""
    if value is None:
        return None
    text = value.strip()
    with localcontext() as context:
        context.prec = max(28, len(text) * 2)
        offset = TIMELINE_OFFSET.fullmatch(text)
        if offset:
            multiplier = {"ms": Decimal("0.001"), "m": Decimal(60), "h": Decimal(3600)}.get(offset[2], Decimal(1))
            return (Decimal(offset[1]) * multiplier).normalize()
        parts = text.split(":")
        if not 1 <= len(parts) <= 3 or any(not TIMELINE_NUMBER.fullmatch(part) for part in parts):
            return None
        seconds = Decimal(0)
        for part in parts:
            seconds = seconds * 60 + Decimal(part)
        return seconds.normalize()


def collect_timeline(
    ttml: str, section: TimelineSection, ancestors: list[TimelineSection], lines: list[TimelineLine]
) -> bool:
    children = child_elements(ttml, section.element)
    if not children:
        return False
    cursor = section.element.content_start
    pending = ""
    for child in children:
        prefix = pending + ttml[cursor:child.start]
        pending = ""
        if is_element(ttml, child.start, "p"):
            begin = timeline_time(attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE))
            if begin is None:
                return False
            lines.append(TimelineLine(child, begin, ancestors, prefix))
        elif is_element(ttml, child.start, "div"):
            if not child_elements(ttml, child):
                pending = prefix + ttml[child.start:child.end]
            else:
                nested = TimelineSection(child, prefix)
                if not collect_timeline(ttml, nested, ancestors + [nested], lines):
                    return False
        else:
            return False
        cursor = child.end
    section.tail = pending + ttml[cursor:section.element.content_end]
    return True


def replace_attribute(tag: str, pattern: re.Pattern, value: str | None) -> str:
    if value is None:
        return tag
    match = pattern.search(tag)
    if match is None:
        return tag
    group = 1 if match[1] is not None else 2
    start, end = match.span(group)
    return tag[:start] + value + tag[end:]


def render_timeline(ttml: str, root: TimelineSection, lines: list[TimelineLine]) -> str:
    result: list[str] = []
    opened: list[TimelineSection] = []
    seen: set[TimelineSection] = set()
    last = {section: index for index, line in enumerate(lines) for section in line.ancestors}

    def close_to(depth: int, index: int) -> None:
        while len(opened) > depth:
            section = opened.pop()
            if last[section] == index:
                result.append(section.tail)
            result.append(ttml[section.element.content_end:section.element.end])

    for index, line in enumerate(lines):
        common = 0
        for previous, current in zip(opened, line.ancestors):
            if previous is not current:
                break
            common += 1
        close_to(common, index - 1)
        for section in line.ancestors[common:]:
            if section not in seen:
                seen.add(section)
                result.append(section.prefix)
            tag = ttml[section.element.start:section.element.content_start]
            tag = replace_attribute(tag, BEGIN_ATTRIBUTE_VALUE, attribute(line.element.attributes, BEGIN_ATTRIBUTE_VALUE))
            ends: list[tuple[Decimal, str]] = []
            for candidate in lines[index:]:
                if section not in candidate.ancestors:
                    break
                value = attribute(candidate.element.attributes, END_ATTRIBUTE_VALUE)
                end = timeline_time(value)
                if end is not None:
                    ends.append((end, value))
            end_value = max(ends, key=lambda item: item[0])[1] if ends else None
            result.append(replace_attribute(tag, END_ATTRIBUTE_VALUE, end_value))
            opened.append(section)
        result.append(line.prefix)
        result.append(ttml[line.element.start:line.element.end])
    close_to(0, len(lines) - 1)
    result.append(root.tail)
    return "".join(result)


def element_content(ttml: str, element: Element) -> str:
    return "" if element.self_closing else ttml[element.content_start:element.content_end]


def format_track(ttml: str, track: Element, lines: list[TimelineLine], merge: bool) -> TrackFormatting | None:
    entries = child_elements(ttml, track)
    if not entries:
        return TrackFormatting(None)
    if any(not is_element(ttml, entry.start, "text") for entry in entries):
        return None
    keys = [attribute(line.element.attributes, KEY_ATTRIBUTE) for line in lines]
    by_key = {attribute(entry.attributes, FOR_ATTRIBUTE): entry for entry in entries}
    if len(by_key) != len(entries) or any(key not in keys for key in by_key):
        return None
    texts = [element_content(ttml, by_key[key]) if key in by_key else ABSENT_TEXT for key in keys]
    texts = [text if text.strip() else ABSENT_TEXT for text in texts]
    if merge:
        groups: dict[Decimal, list[int]] = {}
        for index, line in enumerate(lines):
            groups.setdefault(line.begin, []).append(index)
        for group in groups.values():
            if len(group) < 2:
                continue
            combined = "\n".join(dict.fromkeys(texts[index] for index in group if texts[index].strip()))
            texts[group[0]] = combined or ABSENT_TEXT
            for index in group[1:]:
                texts[index] = ABSENT_TEXT
    prefixes: dict[int, str] = {}
    cursor = track.content_start
    for entry in entries:
        prefixes[entry.start] = ttml[cursor:entry.start]
        cursor = entry.end
    result: list[str] = []
    for index, key in enumerate(keys):
        entry = by_key.get(key)
        if entry is None:
            result.append(f"<text for={quote_raw_attribute_value(key)}>{texts[index]}</text>")
        else:
            result.append(prefixes[entry.start])
            if entry.self_closing:
                result.append(f"<text{entry.attributes}>{texts[index]}</text>")
            else:
                result.extend((ttml[entry.start:entry.content_start], texts[index], ttml[entry.content_end:entry.end]))
    result.append(ttml[cursor:track.content_end])
    formatted = "".join(result)
    return TrackFormatting(None if formatted == element_content(ttml, track) else Edit(track.content_start, track.content_end, formatted))


def format_timeline(ttml: str) -> str:
    body = next_element(ttml, "body", 0)
    if body is None:
        return ttml
    root = TimelineSection(body, "")
    lines: list[TimelineLine] = []
    if not collect_timeline(ttml, root, [], lines) or not lines:
        return ttml
    ordered = sorted(lines, key=lambda line: line.begin)
    edits: list[Edit] = []
    if ordered != lines:
        edits.append(Edit(body.content_start, body.content_end, render_timeline(ttml, root, ordered)))
    keys = [attribute(line.element.attributes, KEY_ATTRIBUTE) for line in ordered]
    if (any(key is None or not key.strip() for key in keys) or len(set(keys)) != len(keys)) and declared_tracks(ttml).any:
        return ttml
    if all(key is not None for key in keys) and len(set(keys)) == len(keys):
        metadata = next_element(ttml, "metadata", 0)
        if metadata is not None:
            for container_name, item_name in (("translations", "translation"), ("transliterations", "transliteration")):
                container = next_element(ttml, container_name, metadata.content_start)
                if container is None or container.end > metadata.content_end:
                    continue
                for track in child_elements(ttml, container):
                    if is_element(ttml, track.start, item_name):
                        formatted = format_track(ttml, track, ordered, container_name == "translations")
                        if formatted is None:
                            return ttml
                        if formatted.edit is not None:
                            edits.append(formatted.edit)
    return apply_edits(ttml, edits)


def declared_tracks(ttml: str) -> DeclaredTracks:
    return DeclaredTracks(
        translations=next_element(ttml, "translations", 0) is not None,
        transliterations=next_element(ttml, "transliterations", 0) is not None,
    )


def unnamed_transliterations(ttml: str) -> list[Element]:
    """The head track's <transliteration> elements that carry no ``xml:lang``."""
    block = next_element(ttml, "transliterations", 0)
    if block is None:
        return []
    unnamed: list[Element] = []
    cursor = block.content_start
    while True:
        item = next_element(ttml, "transliteration", cursor)
        if item is None or item.end > block.content_end:
            break
        cursor = item.end
        if not (attribute(item.attributes, LANGUAGE_ATTRIBUTE) or "").strip():
            unnamed.append(item)
    return unnamed


def transliteration_language(ttml: str, declared: str | None = None) -> str:
    """The ``<transliteration xml:lang>`` tag for a song's own lyrics language."""
    candidates = [declared, document_language(ttml)]
    for candidate in candidates:
        script = transliteration_script_language(candidate)
        if script is not None:
            return f"{script}-Latn"
    return transliteration_language_tag(inferred_lyrics_language(ttml))


def transliteration_script_language(lyrics_language: str | None) -> str | None:
    primary = (lyrics_language or "").strip().replace("_", "-").lower().split("-")[0]
    if primary in ("ja", "jpn"):
        return "ja"
    if primary in ("ko", "kor"):
        return "ko"
    if primary in ("zh", "zho", "chi", "cmn", "yue"):
        return "zh"
    return None


def transliteration_language_tag(lyrics_language: str | None) -> str:
    script = transliteration_script_language(lyrics_language)
    return f"{script}-Latn" if script is not None else FALLBACK_PRONUNCIATION_LANGUAGE


def document_language(ttml: str) -> str | None:
    root = next_element(ttml, "tt", 0)
    return None if root is None else attribute(root.attributes, LANGUAGE_ATTRIBUTE)


def lyrics_language(ttml: str, declared: str | None = None) -> str:
    """
    The language the root ``<tt xml:lang>`` is written with: the caller's explicit
    language, else the document's own declaration, else the script the body is
    actually written in. The root names the **lyrics** language, not the
    transliteration tag, so a declared language is preserved even outside HLE's
    romanization set; only a document that names none and whose body has no
    decisive script keeps the neutral ``und`` (the old hardcoded ``ko`` named a
    language the song was not in).
    """
    explicit = (declared or "").strip()
    if explicit:
        return explicit
    root = (document_language(ttml) or "").strip()
    if root:
        return root
    return inferred_lyrics_language(ttml)


def inferred_lyrics_language(ttml: str) -> str:
    """The language the body's own **lyric text** is written in; ``und`` when unclear.

    Only the lyrics are read. An ``x-translation`` span holds the translation and
    an ``x-roman`` span the romanization, and neither names the language the song
    is sung in. Reading them is what made an English song whose only Han sits in
    its Chinese translation declare ``zh`` — the same primary language as its own
    ``zh-Hans`` translation lane, which the app then declines to offer, because a
    Chinese song needs no Chinese translation. A body with no decisive script is
    ``und``.
    """
    import unicodedata

    body = next_element(ttml, "body", 0)
    if body is None:
        return "und"
    parts: list[str] = []
    append_lyric_text(ttml, body.content_start, body.content_end, parts)
    has_han = False
    for character in "".join(parts):
        name = unicodedata.name(character, "")
        if name.startswith("HIRAGANA") or name.startswith("KATAKANA"):
            return "ja"
        if name.startswith("HANGUL"):
            return "ko"
        if name.startswith("CJK UNIFIED IDEOGRAPH") or name.startswith("CJK COMPATIBILITY IDEOGRAPH"):
            has_han = True
    return "zh" if has_han else "und"


def append_lyric_text(ttml: str, from_: int, to: int, out: list[str]) -> None:
    """Append the lyric text of ``[from_, to)`` to ``out``.

    The content of every translation and romanization span is left out, as is
    every tag, comment and attribute value. A background vocal (``x-bg``) is a
    lyric and is read; an auxiliary span nested inside it is still skipped.
    """
    index = from_
    while index < to:
        open_ = ttml.find("<", index)
        if open_ < 0 or open_ >= to:
            out.append(ttml[index:to])
            return
        out.append(ttml[index:open_])
        literal = literal_end(ttml, open_)
        if literal > 0:
            if ttml.startswith("<![CDATA[", open_) and literal >= open_ + len("<![CDATA[") + len("]]>"):
                out.append(ttml[open_ + len("<![CDATA[") : literal - len("]]>")])
            index = literal
            continue
        end = tag_end(ttml, open_)
        if end < 0:
            return
        name = element_name(ttml, open_, end)
        child = parse_element(ttml, name, open_, end) if name else None
        if child is None:
            index = end + 1
            continue
        role = attribute(child.attributes, ROLE_ATTRIBUTE)
        if role not in (ROLE_TRANSLATION, ROLE_ROMAN):
            append_lyric_text(ttml, child.content_start, child.content_end, out)
        index = child.end


def has_undeclared_inline_track(ttml: str, declared: DeclaredTracks) -> bool:
    """True when the body still carries a kind the head leaves undeclared."""
    body = next_element(ttml, "body", 0)
    if body is None:
        return False
    index = body.content_start
    while index < body.content_end:
        open_ = ttml.find("<", index)
        if open_ < 0 or open_ >= body.content_end:
            return False
        literal = literal_end(ttml, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(ttml, open_)
        if end < 0:
            return False
        role = attribute(ttml[open_ : end + 1], ROLE_ATTRIBUTE)
        if role == ROLE_TRANSLATION and not declared.translations:
            return True
        if role == ROLE_ROMAN and not declared.transliterations:
            return True
        index = end + 1
    return False


def migrate_auxiliary_tracks(
    ttml: str,
    declared: DeclaredTracks,
    transliteration_language_: str,
    unnamed_transliterations_: list[Element],
) -> str:
    """Moves inline ``x-translation`` / ``x-roman`` spans into a head <iTunesMetadata>."""
    # A preserved head track that names no language still has to be named: the
    # tag is the lane's key in the app's own lyric model.
    edits: list[Edit] = [
        Edit(
            item.start + 1 + len("transliteration"),
            item.start + 1 + len("transliteration"),
            f' xml:lang="{transliteration_language_}"',
        )
        for item in unnamed_transliterations_
    ]
    metadata = next_element(ttml, "metadata", 0)
    if metadata is None:
        return apply_edits(ttml, edits)

    translations: list[TrackEntry] = []
    transliterations: list[TrackEntry] = []

    cursor = metadata.end
    while True:
        line = next_element(ttml, "p", cursor)
        if line is None:
            break
        cursor = line.end
        key = attribute(line.attributes, KEY_ATTRIBUTE)
        if key is None:
            continue
        collect_line(ttml, line, key, declared, translations, transliterations, edits)

    # Every keyed line contributes an entry, so a line the source left
    # untranslated still holds its place; whether any entry carries text
    # decides if the track is written at all. The edits are applied either
    # way, because a line may carry a background vocal that needs its body
    # rewrite even when no track follows.
    if not any(e.has_text for e in translations) and not any(
        e.has_text for e in transliterations
    ):
        return apply_edits(ttml, edits)

    edits.append(
        declare_tracks(
            ttml,
            metadata,
            build_tracks(translations, transliterations, transliteration_language_),
            declared,
        )
    )
    return apply_edits(ttml, edits)


def collect_line(
    ttml: str,
    line: Element,
    key: str,
    declared: DeclaredTracks,
    translations: list[TrackEntry],
    transliterations: list[TrackEntry],
    edits: list[Edit],
) -> None:
    # Only one track of each kind survives the parser constraint, so the
    # first translation and first romanization of the line are kept. An
    # empty span is no translation, so a later non-empty one still counts.
    # A background romanization is the exception: a background group may
    # split one reading across several ``x-roman`` spans, so every one of
    # them is kept, concatenated in document order.
    translation = None
    roman = None
    background_translation = None
    background_roman = None

    children = child_elements(ttml, line)
    for child in children:
        role = attribute(child.attributes, ROLE_ATTRIBUTE)
        if role == ROLE_TRANSLATION:
            if not declared.translations:
                if translation is None:
                    translation = auxiliary_text(ttml, child)
                edits.append(removal(ttml, child, line.content_start))
        elif role == ROLE_ROMAN:
            # The app's native parser knows only ``x-bg`` and ``translation``
            # role values, so an inline ``x-roman`` span is never a pronunciation
            # lane to it: on a Word-timed body it is one more untimed lyric span
            # and corrupts the line's word vector. A declared head track is
            # authoritative, so the redundant body copy is dropped; an undeclared
            # one is still migrated into the head as before.
            if not declared.transliterations and roman is None:
                roman = auxiliary_text(ttml, child)
            edits.append(removal(ttml, child, line.content_start))
        elif role == ROLE_BACKGROUND:
            nested = child_elements(ttml, child)
            removals: list[Edit] = []
            for span in nested:
                nested_role = attribute(span.attributes, ROLE_ATTRIBUTE)
                if nested_role == ROLE_TRANSLATION:
                    if not declared.translations:
                        if background_translation is None:
                            background_translation = auxiliary_text(ttml, span)
                        removals.append(removal(ttml, span, child.content_start))
                elif nested_role == ROLE_ROMAN:
                    # Every ``x-roman`` span of the group is kept, in document
                    # order, so a reading split across several of them loses none
                    # of its words. A blank span contributes nothing, exactly as
                    # ``auxiliary_text`` treats every other extraction.
                    if not declared.transliterations:
                        span_text = auxiliary_text(ttml, span)
                        if span_text is not None:
                            background_roman = (background_roman or "") + span_text
                    removals.append(removal(ttml, span, child.content_start))
            edits.extend(background_edits(ttml, line, children, child, nested, removals))

    translations.append(TrackEntry(key, translation, background_translation))
    transliterations.append(TrackEntry(key, roman, background_roman))


def background_edits(
    ttml: str,
    line: Element,
    siblings: list[Element],
    background: Element,
    nested: list[Element],
    auxiliary_removals: list[Edit],
) -> list[Edit]:
    """
    Rewrites the background vocal that stays in the body: its ``begin`` / ``end``
    go, because Apple reads the vocal's extent from its syllables, and a vocal
    starting ahead of the lyrics moves to the front of the line, because Apple
    places the highlight in document order.
    """
    # Without syllables to read the extent from, the attributes are the only
    # timing the vocal has, so they stay and it keeps its place.
    syllable = first_syllable_begin(ttml, nested)
    if syllable is None:
        return auxiliary_removals

    open_tag = ttml[background.start : background.content_start]
    rewrite = auxiliary_removals + [
        Edit(background.start, background.content_start, SPAN_TIMING.sub("", open_tag))
    ]
    lyric = first_syllable_begin(ttml, siblings)
    if siblings[0] is background or lyric is None or syllable >= lyric:
        return rewrite

    return [
        Edit(
            line.content_start,
            line.content_start,
            render(ttml, background.start, background.end, rewrite),
        ),
        removal(ttml, background, line.content_start),
    ]


def first_syllable_begin(ttml: str, candidates: list[Element]) -> float | None:
    """Begin time of the first timed lyric span among candidates, if any."""
    for candidate in candidates:
        if attribute(candidate.attributes, ROLE_ATTRIBUTE) in AUXILIARY_ROLES:
            continue
        value = clock_seconds(attribute(candidate.attributes, BEGIN_ATTRIBUTE_VALUE))
        if value is not None:
            return value
    return None


def declare_tracks(
    ttml: str,
    metadata: Element,
    tracks: str,
    declared: DeclaredTracks,
) -> Edit:
    """
    Places the tracks in the head, joining the <iTunesMetadata> AMLL already
    wrote for <songwriters> -- ahead of what it holds, the order Apple uses --
    rather than opening a second one beside it.
    """
    container = next_element(ttml, "iTunesMetadata", metadata.start)
    if container is not None and container.end > metadata.content_end:
        container = None
    if container is None and metadata.self_closing:
        return Edit(
            metadata.start,
            metadata.end,
            f"<metadata{metadata.attributes}>{wrap_tracks(tracks)}</metadata>",
        )
    if container is None:
        return Edit(metadata.content_end, metadata.content_end, wrap_tracks(tracks))
    if container.self_closing:
        return Edit(
            container.start,
            container.end,
            f"<iTunesMetadata{container.attributes}>{tracks}</iTunesMetadata>",
        )
    # A newly migrated transliteration belongs after a translations track that
    # was already present. Every other new track starts at the front, where
    # build_tracks keeps Apple's kind order.
    if declared.translations and not declared.transliterations:
        existing = next_element(ttml, "translations", container.content_start)
        if existing is not None and existing.end <= container.content_end:
            return Edit(existing.end, existing.end, tracks)
    return Edit(container.content_start, container.content_start, tracks)


def build_tracks(
    translations: list[TrackEntry],
    transliterations: list[TrackEntry],
    transliteration_language_: str = FALLBACK_PRONUNCIATION_LANGUAGE,
) -> str:
    parts: list[str] = []
    append_track(parts, translations, "translations", "translation", TRANSLATION_LANGUAGE, TRANSLATION_TYPE)
    append_track(parts, transliterations, "transliterations", "transliteration", transliteration_language_, None)
    return "".join(parts)


def append_track(
    parts: list[str],
    entries: list[TrackEntry],
    container: str,
    item: str,
    language: str,
    type_: str | None,
) -> None:
    # Nothing to say for this kind at all, so the track is not opened --
    # rather than opened over a column of placeholders.
    if not any(e.has_text for e in entries):
        return
    parts.append(f"<{container}>")
    head = f"<{item}"
    if type_ is not None:
        head += f' type="{type_}"'
    head += f' xml:lang="{language}">'
    parts.append(head)
    for entry in entries:
        parts.append(f"<text for={quote_raw_attribute_value(entry.key)}>")
        parts.append(append_entry_text(entry))
        parts.append("</text>")
    parts.append(f"</{item}>")
    parts.append(f"</{container}>")


def append_entry_text(entry: TrackEntry) -> str:
    """
    The body of one <text>: the line's own text, then its background one
    wrapped in an ``x-bg`` span. A line without text of its own still opens
    with [ABSENT_TEXT], both to keep an entry per line and because Apple
    reads what precedes the ``x-bg`` span as the main translation.
    """
    result = entry.main if entry.main is not None else ABSENT_TEXT
    if entry.background is None:
        return result
    return (
        result
        + f'<span xmlns="{TTML_NAMESPACE}" xmlns:ttm="{TTML_METADATA_NAMESPACE}" ttm:role="{ROLE_BACKGROUND}">'
        + parenthesized(entry.background)
        + "</span>"
    )


def parenthesized(text: str) -> str:
    """Apple parenthesizes a background track; AMLL leaves that to the reader."""
    return text if PARENTHESIZED.match(text) else f"({text})"


def wrap_tracks(tracks: str) -> str:
    return f'<iTunesMetadata xmlns="{ITUNES_NAMESPACE}">{tracks}</iTunesMetadata>'


def auxiliary_text(ttml: str, element: Element) -> str | None:
    """A span's content, or None when it holds nothing worth carrying over."""
    if element.self_closing:
        return None
    content = ttml[element.content_start : element.content_end]
    return content if content.strip() else None


def removal(ttml: str, element: Element, lower_bound: int) -> Edit:
    """Deletes the auxiliary span together with the whitespace that preceded it."""
    start = element.start
    while start > lower_bound and ttml[start - 1].isspace():
        start -= 1
    return Edit(start, element.end, "")


# --- Root / tag normalization -----------------------------------------


def normalize_tags(ttml: str, word_timed: bool, language: str) -> str:
    """
    Declares the timing mode and resolved lyric language on the root and drops
    the empty default namespace override from every other element.
    """
    output: list[str] = []
    index = 0
    root_seen = False
    while index < len(ttml):
        open_ = ttml.find("<", index)
        if open_ < 0:
            output.append(ttml[index:])
            break
        output.append(ttml[index:open_])
        literal = literal_end(ttml, open_)
        if literal > 0:
            output.append(ttml[open_:literal])
            index = literal
            continue
        end = tag_end(ttml, open_)
        if end < 0:
            output.append(ttml[open_:])
            break
        tag = ttml[open_ : end + 1]
        if not root_seen and is_element(ttml, open_, "tt"):
            root_seen = True
            output.append(with_root_attributes(tag, word_timed, language))
        elif tag.startswith("</"):
            output.append(tag)
        else:
            output.append(EMPTY_DEFAULT_NAMESPACE.sub("", tag))
        index = end + 1
    return "".join(output)


def with_root_attributes(tag: str, word_timed: bool, language: str) -> str:
    insert_at = len(tag) - 2 if tag.endswith("/>") else len(tag) - 1
    # Both attributes are authoritative here, so any inherited value is
    # dropped before the resolved one is appended.
    head = tag[:insert_at]
    head = LANGUAGE_ATTRIBUTE.sub("", head)
    head = TIMING_ATTRIBUTE_VALUE.sub("", head)
    additions: list[str] = []
    if not ITUNES_DECLARATION.search(head):
        additions.append(f' xmlns:itunes="{ITUNES_NAMESPACE}"')
    additions.append(f' itunes:timing="{"Word" if word_timed else "Line"}"')
    additions.append(f' xml:lang="{language}"')
    return head.rstrip() + "".join(additions) + tag[insert_at:]


def has_timed_span(text: str, body: Element) -> bool:
    index = body.content_start
    while index < body.content_end:
        open_ = text.find("<", index)
        if open_ < 0 or open_ >= body.content_end:
            return False
        literal = literal_end(text, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(text, open_)
        if end < 0:
            return False
        if is_element(text, open_, "span") and (
            attribute(text[open_ : end + 1], BEGIN_ATTRIBUTE_VALUE) is not None
        ):
            return True
        index = end + 1
    return False


def apply_edits(ttml: str, edits: list[Edit]) -> str:
    return ttml if not edits else render(ttml, 0, len(ttml), edits)


def render(ttml: str, from_: int, to: int, edits: list[Edit]) -> str:
    """[ttml] between [from_] and [to], with every edit falling inside applied."""
    output: list[str] = []
    cursor = from_
    for edit in sorted(edits, key=lambda e: e.start):
        if edit.start < cursor or edit.end > to:
            continue
        output.append(ttml[cursor : edit.start])
        output.append(edit.replacement)
        cursor = edit.end
    output.append(ttml[cursor:to])
    return "".join(output)


# --- Input policy (mirrors TtmlInputPolicy in the module) -------------


def is_acceptable(ttml: str) -> bool:
    """Structural acceptance check matching the module's TtmlInputPolicy."""
    if not ttml:
        return False
    if len(ttml.encode("utf-8")) > 512 * 1024:
        return False
    lower = ttml.lower()
    if "<tt" not in lower or "<body" not in lower:
        return False
    if _count_tag(lower, "<p") > 4096:
        return False
    if _count_tag(lower, "<span") > 65536:
        return False
    return True


def _count_tag(text: str, tag: str) -> int:
    count = 0
    index = 0
    while True:
        index = text.find(tag, index)
        if index < 0:
            break
        nxt = index + len(tag)
        if nxt >= len(text) or text[nxt] in (" ", ">", "\t", "\n"):
            count += 1
        index = nxt
    return count


# --- CLI ---------------------------------------------------------------


def _fetch_adam(adam_id: str) -> str:
    url = f"{AMLL_TTML_DB_BASE}/am-lyrics/{adam_id}.ttml"
    request = urllib.request.Request(url, headers={"User-Agent": "amll-to-apple-ttml"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def _read_input(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def _write_output(path: str | None, content: str) -> None:
    if path is None or path == "-":
        sys.stdout.write(content)
        return
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(content)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert AMLL TTML into Apple Music TTML "
        "(port of the AM++ module AmllTtmlFormatConverter)."
    )
    parser.add_argument("input", nargs="?", help="input .ttml file, or '-' for stdin")
    parser.add_argument("-o", "--output", help="output file; default stdout")
    parser.add_argument(
        "--adam",
        metavar="ID",
        help="fetch <ID>.ttml from the amll-dev/amll-ttml-db repository instead of a file",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="run the module's structural acceptance policy on the result",
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="run the ported unit-test fixtures and exit",
    )
    args = parser.parse_args(argv)

    if args.selftest:
        return _selftest()
    if args.adam and args.input:
        parser.error("--adam and a positional input are mutually exclusive")
    if not args.adam and not args.input:
        parser.error("an input file (or --adam) is required")

    try:
        source = _fetch_adam(args.adam) if args.adam else _read_input(args.input)
    except (OSError, urllib.error.HTTPError, urllib.error.URLError) as error:
        print(f"error: cannot read input: {error}", file=sys.stderr)
        return 2

    conversion = to_apple_format(source)
    print(f"converted: {conversion.converted}", file=sys.stderr)
    if args.validate and not is_acceptable(conversion.ttml):
        print("error: result fails the structural acceptance policy", file=sys.stderr)
        return 3
    _write_output(args.output, conversion.ttml)
    return 0


# --- Self-test (fixtures ported from AmllTtmlFormatConverterTest) ------


def _fail(message: str) -> int:
    print(f"FAIL: {message}", file=sys.stderr)
    return 1


def _selftest() -> int:
    checks: list[tuple[str, Callable[[], bool]]] = []

    amll_format = textwrap.dedent(
        """\
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:amll="http://www.example.com/ns/amll">
        <head><metadata xmlns=""><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body dur="00:04.000"><div xmlns="" begin="00:01.000" end="00:04.000">
        <p begin="00:01.000" end="00:02.000" ttm:agent="v1" itunes:key="L1">
        <span begin="00:01.000" end="00:01.500">aa</span> <span begin="00:01.500" end="00:02.000">bb</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
        <span ttm:role="x-roman">R1</span>
        </p>
        <p begin="00:03.000" end="00:04.000" ttm:agent="v1" itunes:key="L2">
        <span begin="00:03.000" end="00:04.000">cc</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T2</span>
        <span ttm:role="x-roman">R2</span>
        </p></div></body></tt>
        """
    )

    def check_inline_migration() -> bool:
        result = to_apple_format(amll_format)
        assert result.converted
        assert (
            '<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">'
            in result.ttml
        )
        assert (
            '<translations><translation type="subtitle" xml:lang="zh-Hans">'
            "<text for=\"L1\">T1</text><text for=\"L2\">T2</text>"
            "</translation></translations>" in result.ttml
        )
        assert (
            '<transliterations><transliteration xml:lang="und-Latn">'
            "<text for=\"L1\">R1</text><text for=\"L2\">R2</text>"
            "</transliteration></transliterations>" in result.ttml
        )
        body = result.ttml.split("<body", 1)[1]
        assert "x-translation" not in body and "x-roman" not in body
        assert '<span begin="00:01.000" end="00:01.500">aa</span>' in body
        root = result.ttml.split(">", 1)[0]
        assert 'itunes:timing="Word"' in root
        assert 'xml:lang="und"' in root
        assert 'xmlns=""' not in result.ttml
        assert "</span> <span begin=\"00:01.500\"" in result.ttml
        return True

    checks.append(("inline spans migrate into head tracks", check_inline_migration))

    def check_line_timed() -> bool:
        # An untimed lyric span, because a bare-text line now takes a timed span
        # of its own and is no longer a body without timed spans.
        line_timed = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span>a line</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
            """
        )
        root = to_apple_format(line_timed).ttml.split(">", 1)[0]
        assert 'itunes:timing="Line"' in root
        assert 'xml:lang="und"' in root
        return True

    checks.append(("no timed spans means line timing", check_line_timed))

    def check_head_track_does_not_time_body() -> bool:
        # The body's lyric is an untimed span: a bare-text line would now take a
        # timed span of its own, which is exactly what this check must not have.
        timed_head_line_body = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span>aa</span> <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(timed_head_line_body)
        assert result.converted
        assert 'itunes:timing="Line"' in result.ttml.split(">", 1)[0]
        return True

    checks.append(("a timed head track does not time the body", check_head_track_does_not_time_body))

    def check_translation_never_names_lyrics() -> bool:
        # Apple reads the root xml:lang as the song's language and withholds a
        # translation that matches it, so an English song whose only Han sits in
        # its own inline Chinese translation must not declare zh.
        translated_english = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">It feels like a perfect night</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">这夜晚完美得刚好</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(translated_english)
        root = result.ttml.split(">", 1)[0]
        assert 'xml:lang="zh"' not in root
        assert 'xml:lang="und"' in root
        assert '<text for="L1">这夜晚完美得刚好</text>' in result.ttml

        # The exclusion must not break the real inference: kana still decides.
        translated_japanese = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="0.5">何</span><span begin="0.5" end="1.0">し</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">什么</span>
            <span ttm:role="x-roman">nani shi</span>
            </p></div></body></tt>
            """
        )
        japanese = to_apple_format(translated_japanese).ttml
        assert 'xml:lang="ja"' in japanese.split(">", 1)[0]
        assert '<transliteration xml:lang="ja-Latn">' in japanese
        return True

    checks.append(
        ("a translation never names the lyrics language", check_translation_never_names_lyrics)
    )

    def check_declared_replaced_not_duplicated() -> bool:
        declared = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Line" xml:lang="ja">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
            """
        )
        root = to_apple_format(declared).ttml.split(">", 1)[0]
        assert root.count("itunes:timing") == 1
        assert root.count("xml:lang") == 1
        assert 'itunes:timing="Word"' in root
        assert 'xml:lang="ja"' in root
        return True

    checks.append(("root attributes replace inherited ones with the resolved language", check_declared_replaced_not_duplicated))

    def check_japanese_root_language() -> bool:
        # The reported メタモリボン shape: no root xml:lang, kana lyrics and a
        # word-timed x-roman. The root must be `ja`, not the old hardcoded `ko`,
        # and the head entry must keep its per-word spans.
        japanese = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="0.5">何</span><span begin="0.5" end="1.0">し</span>
            <span ttm:role="x-roman"><span begin="0.0" end="0.5">nani</span><span begin="0.5" end="1.0">shi</span></span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(japanese)
        root = result.ttml.split(">", 1)[0]
        assert 'xml:lang="ja"' in root
        assert (
            '<transliterations><transliteration xml:lang="ja-Latn">'
            '<text for="L1"><span begin="0.0" end="0.5">nani</span>'
            '<span begin="0.5" end="1.0">shi</span></text>'
            "</transliteration></transliterations>" in result.ttml
        )
        return True

    checks.append(("a Japanese body names the root language and keeps the word slices", check_japanese_root_language))

    def check_gapped_placeholder() -> bool:
        gapped = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p>
            <p begin="2.0" end="3.0" itunes:key="L3">
            <span begin="2.0" end="3.0">cc</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T3</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(gapped).ttml
        assert (
            '<translations><translation type="subtitle" xml:lang="zh-Hans">'
            '<text for="L1">T1</text>'
            '<text for="L2"> </text>'
            '<text for="L3">T3</text>'
            "</translation></translations>" in result
        )
        return True

    checks.append(("untranslated lines keep a placeholder entry", check_gapped_placeholder))

    def check_roman_only() -> bool:
        roman_only = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(roman_only).ttml
        assert "<translations>" not in result
        assert (
            '<transliterations><transliteration xml:lang="und-Latn">'
            '<text for="L1">R1</text><text for="L2"> </text>'
            "</transliteration></transliterations>" in result
        )
        return True

    checks.append(("a kind nobody speaks for gets no track", check_roman_only))

    def check_unnamed_head_transliteration() -> bool:
        # AMLL's own shape: the head declares the track, the entry names no
        # language, and the lyrics are Japanese. The tag has to be stamped, or
        # the app parses the romanization under no language and cannot select it.
        unnamed_head = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1">ki</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">\u304d</span>
            <span ttm:role="x-roman">ki</span>
            </p></div></body></tt>
            """
        )
        assert (
            '<transliteration xml:lang="ja-Latn"><text for="L1">ki</text>'
            in to_apple_format(unnamed_head).ttml
        )
        # An entry that already names a language is never rewritten.
        named_head = unnamed_head.replace(
            "<transliteration>", '<transliteration xml:lang="en-Latn">'
        )
        stamped = to_apple_format(named_head).ttml
        assert '<transliteration xml:lang="en-Latn">' in stamped
        assert "ja-Latn" not in stamped
        # A declared language wins over the script the lyrics are written in.
        assert (
            '<transliteration xml:lang="zh-Latn">'
            in to_apple_format(unnamed_head, lyric_language="zh").ttml
        )
        return True

    checks.append(
        (
            "an unnamed head transliteration is stamped with the song's tag",
            check_unnamed_head_transliteration,
        )
    )

    def check_background_only_line() -> bool:
        background_only = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L9">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(background_only).ttml
        assert (
            '<text for="L9"> <span xmlns="http://www.w3.org/ns/ttml" '
            'xmlns:ttm="http://www.w3.org/ns/ttml#metadata" ttm:role="x-bg">(BT)</span></text>' in result
        )
        return True

    checks.append(("background-only translation opens with the placeholder", check_background_only_line))

    def check_leading_background_moves() -> bool:
        leading = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="1.0" end="3.0" itunes:key="L1">
            <span begin="2.0" end="3.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
            """
        )
        body = to_apple_format(leading).ttml.split("<body", 1)[1]
        assert (
            'itunes:key="L1">'
            '<span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span></span>' in body
        )
        assert body.index("x-bg") < body.index(">aa<")
        assert body.count("x-bg") == 1
        return True

    checks.append(("early background vocal moves to the front", check_leading_background_moves))

    def check_split_background_roman() -> bool:
        # A background vocal whose reading is split across several ``x-roman``
        # spans, one per word. The old port kept only the first and dropped the
        # rest; every span must now contribute, concatenated in document order.
        split_background_roman = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-roman"><span begin="1.0" end="1.5">bei</span></span>
            <span ttm:role="x-roman"><span begin="1.5" end="2.0">jing</span></span></span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(split_background_roman).ttml
        assert (
            f'<text for="L1"> {BG_SPAN_OPEN}'
            '(<span begin="1.0" end="1.5">bei</span>'
            '<span begin="1.5" end="2.0">jing</span>)'
            "</span></text>" in result
        )
        # Both word slices survive, and neither copy is left in the body.
        assert ">bei<" in result and ">jing<" in result
        assert "x-roman" not in result.split("<body", 1)[1]
        return True

    checks.append(("every romanization of a background group is kept", check_split_background_roman))

    def check_single_background_roman() -> bool:
        single_background_roman = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-roman">beijing</span></span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(single_background_roman).ttml
        assert (
            f'<text for="L1"> {BG_SPAN_OPEN}(beijing)</span></text>' in result
        )
        assert "x-roman" not in result.split("<body", 1)[1]
        return True

    checks.append(("a single background romanization is unchanged", check_single_background_roman))

    def check_bare_text_line() -> bool:
        # The AMLL axis tool leaves a one-word line as bare text; the word must
        # take the line's own extent so the per-word matcher can align it.
        legacy_lines = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="00:23.038" end="00:24.843" ttm:agent="v1" itunes:key="L7">Yeah</p>
            <p begin="00:25.000" end="00:26.000" itunes:key="L8"><span begin="00:25.000" end="00:26.000">Whoa</span></p>
            </div></body></tt>
            """
        )
        result = to_apple_format(legacy_lines)
        assert result.converted
        assert (
            '<p begin="00:23.038" end="00:24.843" ttm:agent="v1" itunes:key="L7">'
            '<span begin="00:23.038" end="00:24.843">Yeah</span></p>' in result.ttml
        )
        # A line that already carries a span is untouched.
        assert (
            '<p begin="00:25.000" end="00:26.000" itunes:key="L8">'
            '<span begin="00:25.000" end="00:26.000">Whoa</span></p>' in result.ttml
        )
        return True

    checks.append(("a bare text line gets its timed span", check_bare_text_line))

    def check_background_wrapper_shape() -> bool:
        # Single-layer: role outer, timing and text inner, outer untimed.
        single_layer = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="2:32.000" end="2:34.000" itunes:key="L1">
            <span begin="2:32.000" end="2:32.296">aa</span>
            <span ttm:role="x-bg" begin="2:32.296" end="2:33.666">(Ooh)</span>
            </p></div></body></tt>
            """
        )
        body = to_apple_format(single_layer).ttml.split("<body", 1)[1]
        assert (
            '<span ttm:role="x-bg"><span begin="2:32.296" end="2:33.666">(Ooh)</span></span>'
            in body
        )
        assert '<span ttm:role="x-bg" begin=' not in body
        assert '<span ttm:role="x-bg" end=' not in body

        # Two-layer: the redundant timing on the wrapper goes, inner spans stay.
        two_layer = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="1.5">(Al</span><span begin="1.5" end="2.0">right)</span></span>
            </p></div></body></tt>
            """
        )
        two_layer_body = to_apple_format(two_layer).ttml.split("<body", 1)[1]
        assert (
            '<span ttm:role="x-bg"><span begin="1.0" end="1.5">(Al</span>'
            '<span begin="1.5" end="2.0">right)</span></span>' in two_layer_body
        )
        assert '<span ttm:role="x-bg" begin=' not in two_layer_body
        return True

    checks.append(("an x-bg wrapper is role-only and untimed", check_background_wrapper_shape))

    def check_background_fallback_timing() -> bool:
        # Nothing to move into an inner span, so the wrapper's own extent stays.
        empty_background = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="2.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0">   </span>
            </p></div></body></tt>
            """
        )
        assert '<span ttm:role="x-bg" begin="1.0" end="2.0">' in to_apple_format(
            empty_background
        ).ttml
        return True

    checks.append(("an empty x-bg keeps its fallback timing", check_background_fallback_timing))

    def check_legacy_shapes_idempotent() -> bool:
        legacy = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="2.0" itunes:key="L1">Yeah</p>
            <p begin="2.0" end="4.0" itunes:key="L2">
            <span begin="2.0" end="3.0">aa</span>
            <span ttm:role="x-bg" begin="3.0" end="3.5">(Hey!)</span>
            <span ttm:role="x-bg" begin="3.5" end="4.0"><span begin="3.5" end="3.75">(Ooh</span><span begin="3.75" end="4.0">!)</span></span>
            </p></div></body></tt>
            """
        )
        once = to_apple_format(legacy)
        twice = to_apple_format(once.ttml)
        assert once.converted
        assert not twice.converted and twice.ttml == once.ttml
        assert '<span begin="0.0" end="2.0">Yeah</span>' in once.ttml
        assert (
            '<span ttm:role="x-bg"><span begin="3.0" end="3.5">(Hey!)</span></span>' in once.ttml
        )
        assert (
            '<span ttm:role="x-bg"><span begin="3.5" end="3.75">(Ooh</span>'
            '<span begin="3.75" end="4.0">!)</span></span>' in once.ttml
        )
        assert '<span ttm:role="x-bg"><span ttm:role="x-bg">' not in once.ttml
        assert '<span ttm:role="x-bg" begin=' not in once.ttml.split("<body", 1)[1]
        return True

    checks.append(("the legacy shapes normalize idempotently", check_legacy_shapes_idempotent))

    def check_exposed_bare_line() -> bool:
        # The source hid the lyric beside the auxiliary span, so the first run
        # must already emit the wrapped shape rather than leaving it to a second.
        hidden_lyric = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1"><span ttm:role="x-translation" xml:lang="zh-CN">T1</span>Yeah</p></div></body></tt>
            """
        )
        once = to_apple_format(hidden_lyric)
        twice = to_apple_format(once.ttml)
        assert once.converted
        assert not twice.converted and twice.ttml == once.ttml
        assert (
            '<p begin="0.0" end="1.0" itunes:key="L1">'
            '<span begin="0.0" end="1.0">Yeah</span></p>' in once.ttml
        )
        return True

    checks.append(("a line exposed bare by migration is wrapped", check_exposed_bare_line))

    def check_head_lane_background() -> bool:
        head_lane = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">T1<span ttm:role="x-bg" begin="0.5" end="1.0">(BT)</span></text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
            """
        )
        result = to_apple_format(head_lane)
        assert result.converted
        assert (
            '<text for="L1">T1<span ttm:role="x-bg">'
            '<span begin="0.5" end="1.0">(BT)</span></span></text>' in result.ttml
        )
        # A converter-written lane entry is canonical and left alone.
        canonical = head_lane.replace(
            '<span ttm:role="x-bg" begin="0.5" end="1.0">(BT)</span>',
            '<span ttm:role="x-bg">(BT)</span>',
        )
        assert not to_apple_format(canonical).converted
        return True

    checks.append(("a head lane background is normalized too", check_head_lane_background))

    def check_declared_translation_lane_untouched() -> bool:
        # The injection-path shape: a translations lane already in the head while
        # the body still has an undeclared romanization, so migration and the
        # post-migration pass both run beside that lane. Its `<text for=…>`
        # bodies are read verbatim by the app and must never be given a span.
        declared_lane = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">译文一</text><text for="L2"><span ttm:role="x-bg">(合)</span></text><text for="L3">译文三</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span></p>
            <p begin="1.0" end="2.0" itunes:key="L2"><span ttm:role="x-roman">R2</span>hidden</p>
            <p begin="2.0" end="3.0" itunes:key="L3">bare</p>
            </div></body></tt>
            """
        )
        result = to_apple_format(declared_lane)
        head = result.ttml.split("<body", 1)[0]
        assert (
            "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">"
            "<text for=\"L1\">译文一</text>"
            "<text for=\"L2\"><span ttm:role=\"x-bg\">(合)</span></text>"
            "<text for=\"L3\">译文三</text>"
            "</translation></translations>" in head
        )
        assert '<text for="L1"><span' not in head
        assert '<text for="L3"><span' not in head
        # The undeclared romanization still migrates, and the body lines
        # migration exposed are wrapped as the body rule says.
        assert "<transliterations><transliteration" in head
        assert (
            '<p begin="1.0" end="2.0" itunes:key="L2">'
            '<span begin="1.0" end="2.0">hidden</span></p>' in result.ttml
        )
        assert (
            '<p begin="2.0" end="3.0" itunes:key="L3">'
            '<span begin="2.0" end="3.0">bare</span></p>' in result.ttml
        )
        twice = to_apple_format(result.ttml)
        assert not twice.converted and twice.ttml == result.ttml
        return True

    checks.append(
        ("a declared translation lane keeps its text", check_declared_translation_lane_untouched)
    )

    def check_declared_and_inline() -> bool:
        declared_and_inline = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1">ro</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            <span ttm:role="x-translation">T1</span></p></div></body></tt>
            """
        )
        result = to_apple_format(declared_and_inline).ttml
        assert result.count("<transliterations>") == 1
        assert '<text for="L1">R1</text>' not in result
        # A declared romanization track is authoritative: the redundant inline
        # `x-roman` span is dropped from the body because the native parser does
        # not know the role and would read it as an untimed lyric span.
        assert "x-roman" not in result
        assert "x-roman" not in result.split("<body", 1)[1]
        # The translation kind is undeclared, so its inline span is still migrated.
        assert '<text for="L1">T1</text>' in result
        return True

    checks.append(("a declared romanization kind drops its inline spans", check_declared_and_inline))

    def check_mixed_is_idempotent() -> bool:
        mixed = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text><text for="L2"><span begin="1.0" end="2.0">ma</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div>
            <p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span></p>
            <p begin="1.0" end="2.0" itunes:key="L2"><span begin="1.0" end="2.0">bb</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T2</span></p>
            </div></body></tt>
            """
        )
        once = to_apple_format(mixed)
        assert once.converted
        assert once.ttml.count("<iTunesMetadata") == 1
        assert once.ttml.count("<transliterations>") == 1
        assert once.ttml.index("<translations>") < once.ttml.index("<transliterations>")
        assert (
            '<transliterations><transliteration xml:lang="und-Latn"><text for="L1">'
            '<span begin="0.0" end="1.0">ro</span></text>' in once.ttml
        )
        assert "x-translation" not in once.ttml.split("<body", 1)[1]
        assert is_acceptable(once.ttml)
        twice = to_apple_format(once.ttml)
        assert not twice.converted and twice.ttml == once.ttml
        return True

    checks.append(("mixed document is migrated once and idempotent", check_mixed_is_idempotent))

    def check_no_key_keeps_spans() -> bool:
        no_key = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(no_key).ttml
        assert "<iTunesMetadata" not in result
        assert '<span ttm:role="x-translation" xml:lang="zh-CN">T1</span>' in result
        return True

    checks.append(("lines without a key keep their auxiliary spans", check_no_key_keeps_spans))

    def check_plain_root() -> bool:
        plain = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
            """
        )
        result = to_apple_format(plain)
        assert result.converted
        assert "<iTunesMetadata" not in result.ttml
        assert 'xmlns=""' not in result.ttml
        root = result.ttml.split(">", 1)[0]
        assert 'itunes:timing="Word"' in root
        assert 'xml:lang="und"' in root
        return True

    checks.append(("plain document still gets the root attributes", check_plain_root))

    def check_verbatim() -> bool:
        tricky = textwrap.dedent(
            """\
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- xmlns="" in a comment -->
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><amll:meta key="musicName" value="a &gt; b xmlns=&quot;&quot;"/></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">2 &lt; 3</span>
            <span ttm:role="x-translation">&lt;kept&gt;</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(tricky).ttml
        assert '<?xml version="1.0" encoding="UTF-8"?>' in result
        assert '<!-- xmlns="" in a comment -->' in result
        assert 'value="a &gt; b xmlns=&quot;&quot;"' in result
        assert ">2 &lt; 3<" in result
        assert "<text for=\"L1\">&lt;kept&gt;</text>" in result
        return True

    checks.append(("comments cdata and entities are copied verbatim", check_verbatim))

    def check_already_apple() -> bool:
        apple_format = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation xml:lang="zh-CN"><text for="L1">T1</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
            """
        )
        result = to_apple_format(apple_format)
        assert not result.converted and result.ttml == apple_format
        return True

    checks.append(("an Apple document is left untouched", check_already_apple))

    def check_songwriters_join() -> bool:
        songwriters_only = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <songwriters><songwriter>Someone</songwriter></songwriters>
            </iTunesMetadata></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(songwriters_only)
        assert result.converted
        assert result.ttml.count("<iTunesMetadata") == 1
        assert (
            '<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">'
            '<translations><translation type="subtitle" xml:lang="zh-Hans">'
            '<text for="L1">T1</text></translation></translations>' in result.ttml
        )
        assert result.ttml.index("<translations>") < result.ttml.index("<songwriters>")
        return True

    checks.append(("tracks join the existing songwriters metadata", check_songwriters_join))

    def check_second_run_idempotent() -> bool:
        once = to_apple_format(amll_format)
        twice = to_apple_format(once.ttml)
        assert not twice.converted and twice.ttml == once.ttml
        return True

    checks.append(("the rewrite is idempotent", check_second_run_idempotent))

    def check_malformed_never_throws() -> bool:
        assert to_apple_format("").ttml == ""
        assert to_apple_format("plain text").ttml == "plain text"
        for malformed in ("<tt><body>", '<tt><head><metadata/></head><body><p itunes:key="L1">'):
            result = to_apple_format(malformed)
            assert "<iTunesMetadata" not in result.ttml
            assert result.ttml.startswith("<tt ")
            assert 'xml:lang="und"' in result.ttml
        return True

    checks.append(("malformed and empty input never throws", check_malformed_never_throws))

    for name, check in checks:
        try:
            check()
        except AssertionError as error:
            return _fail(f"{name}: {error}")
    print(f"selftest: {len(checks)} checks passed")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", newline="")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
