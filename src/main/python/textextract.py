#!/usr/bin/env python3
"""Liest den Text aus Dateien, die DocuMan unverändert ablegt und nicht in PDF umwandelt.

Dieselbe Datei liegt in DocuMan (documents/textextract.py) und in DocuMan-Java
(src/main/python/textextract.py) und muss in beiden gleich bleiben. Sie braucht nur die
Standardbibliothek, für alte Office-Dateien zusätzlich catdoc.

Aufruf: textextract.py DATEI DATEITYP. Der Text geht als UTF-8 nach stdout. Unbekannte
oder beschädigte Dateien liefern leeren Text, nie einen Fehler.
"""

import email
import re
import shutil
import subprocess
import sys
import zipfile
from email import policy
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree

MAX_CHARACTERS = 2_000_000
# Schutz vor ZIP-Bomben: Größere XML-Teile werden nicht entpackt.
MAX_PART_BYTES = 50 * 1024 * 1024
LEGACY_TIMEOUT_SECONDS = 120
TEXT_TYPES = {
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
    "application/javascript",
}
LEGACY_TOOLS = {
    ".doc": "catdoc",
    ".dot": "catdoc",
    ".xls": "xls2csv",
    ".xlt": "xls2csv",
    ".ppt": "catppt",
    ".pps": "catppt",
}
LEGACY_TYPES = {
    "application/msword": "catdoc",
    "application/vnd.ms-excel": "xls2csv",
    "application/vnd.ms-powerpoint": "catppt",
}


def decode_text(raw):
    for encoding in ("utf-8-sig", "cp1252"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1")


def _local(tag):
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def xml_text(data, paragraphs, text_tags=None, tabs=(), breaks=(), spaces=()):
    """Text eines XML-Teils. Nach den Elementen in paragraphs beginnt eine neue Zeile.

    Ohne text_tags zählt aller Text, sonst nur der in diesen Elementen. Der Stapel statt
    einer Rekursion hält auch sehr tief verschachtelte Dateien aus.
    """
    out = []
    stack = [(ElementTree.fromstring(data), False, None)]
    while stack:
        node, parent_keeps, closing = stack.pop()
        name = _local(node.tag)
        if closing:
            if name in paragraphs:
                out.append("\n")
            if node.tail and parent_keeps:
                out.append(node.tail)
            continue
        keeps = text_tags is None or name in text_tags
        if name in tabs:
            out.append("\t")
        elif name in breaks:
            out.append("\n")
        elif name in spaces:
            out.append(" ")
        if node.text and keeps:
            out.append(node.text)
        stack.append((node, parent_keeps, True))
        stack.extend((child, keeps, None) for child in reversed(node))
    return "".join(out)


def _part(archive, name):
    info = archive.getinfo(name)
    if info.file_size > MAX_PART_BYTES:
        return b""
    return archive.read(info)


def _numbered(names, pattern):
    matches = ((re.fullmatch(pattern, name), name) for name in names)
    found = [(int(match.group(1)), name) for match, name in matches if match]
    return [name for _, name in sorted(found)]


def _office_zip(path):
    """Word, Excel, PowerPoint und OpenDocument sind ZIP-Archive mit XML darin."""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if "mimetype" in names and _part(archive, "mimetype").startswith(b"application/vnd.oasis"):
            if "content.xml" not in names:
                return ""
            return xml_text(
                _part(archive, "content.xml"),
                {"p", "h"},
                tabs={"tab"},
                breaks={"line-break"},
                spaces={"s"},
            )
        if "word/document.xml" in names:
            parts = ["word/document.xml"]
            margins = r"word/(header|footer)\d*\.xml"
            parts += sorted(name for name in names if re.fullmatch(margins, name))
            return "\n".join(
                xml_text(_part(archive, name), {"p"}, {"t"}, {"tab"}, {"br", "cr"})
                for name in parts
            )
        if "ppt/presentation.xml" in names:
            slides = _numbered(names, r"ppt/slides/slide(\d+)\.xml")
            texts = (xml_text(_part(archive, name), {"p"}, {"t"}, breaks={"br"}) for name in slides)
            return "\n".join(texts)
        if "xl/workbook.xml" in names:
            parts = ["xl/sharedStrings.xml"] if "xl/sharedStrings.xml" in names else []
            texts = [xml_text(_part(archive, name), {"si"}, {"t"}) for name in parts]
            # Direkt in Zellen stehender Text, z. B. aus anderen Programmen als Excel.
            for name in _numbered(names, r"xl/worksheets/sheet(\d+)\.xml"):
                texts.append(xml_text(_part(archive, name), {"is"}, {"t"}))
            return "\n".join(text for text in texts if text.strip())
    return ""


class _HtmlText(HTMLParser):
    BLOCKS = {"p", "div", "br", "li", "tr", "section", "article"} | {f"h{n}" for n in range(1, 7)}
    HIDDEN = {"script", "style", "head", "title", "noscript", "template"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out, self.hidden = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in self.HIDDEN:
            self.hidden += 1
        elif tag in self.BLOCKS:
            self.out.append("\n")
        elif tag == "td":
            self.out.append("\t")

    def handle_endtag(self, tag):
        if tag in self.HIDDEN:
            self.hidden = max(0, self.hidden - 1)
        elif tag in self.BLOCKS:
            self.out.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.out.append(data)


def html_text(markup):
    parser = _HtmlText()
    parser.feed(markup)
    parser.close()
    return "".join(parser.out)


_RTF_TOKEN = re.compile(
    r"\\([a-z]{1,32})(-?\d{1,10})? ?|\\'([0-9a-f]{2})|\\([^a-z])|([{}])|[\r\n]+|([^\\{}\r\n]+)",
    re.IGNORECASE,
)
# Ziele, deren Inhalt kein Fließtext ist.
_RTF_HIDDEN = {
    "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer",
    "headerl", "headerr", "footerl", "footerr", "listtable", "listoverridetable", "rsidtbl",
    "generator", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl",
    "filetbl", "revtbl", "fldinst",
}  # fmt: skip


def rtf_text(raw):
    out, stack, pending = [], [], bytearray()
    hidden, skip, uc = False, 0, 1
    for match in _RTF_TOKEN.finditer(raw.decode("latin-1")):
        word, argument, code, symbol, brace, plain = match.groups()
        if code is None and pending:
            out.append(pending.decode("cp1252", "replace"))
            pending.clear()
        if brace == "{":
            stack.append((hidden, uc))
        elif brace == "}":
            hidden, uc = stack.pop() if stack else (hidden, uc)
        elif symbol is not None:
            if symbol == "*":
                hidden = True
            elif not hidden and symbol in "\\{}":
                out.append(symbol)
            elif not hidden and symbol == "~":
                out.append("\u00a0")
        elif word is not None:
            name = word.lower()
            if name in _RTF_HIDDEN:
                hidden = True
            elif hidden:
                continue
            elif name in ("par", "line", "sect", "page", "row"):
                out.append("\n")
            elif name in ("tab", "cell"):
                out.append("\t")
            elif name == "uc":
                uc = int(argument or 1)
            elif name == "u" and argument:
                out.append(chr(int(argument) % 65536))
                skip = uc
        elif code is not None:
            if skip:
                skip -= 1
            elif not hidden:
                pending.append(int(code, 16))
        elif plain and not hidden:
            dropped = min(skip, len(plain))
            skip -= dropped
            out.append(plain[dropped:])
    if pending:
        out.append(pending.decode("cp1252", "replace"))
    return "".join(out)


def mail_text(raw):
    message = email.message_from_bytes(raw, policy=policy.default)
    lines = []
    for label, header in (("Von", "From"), ("An", "To"), ("Datum", "Date"), ("Betreff", "Subject")):
        if message[header]:
            lines.append(f"{label}: {message[header]}")
    plain, markup, attachments = [], [], []
    for part in message.walk():
        if part.is_multipart():
            continue
        if part.get_content_disposition() == "attachment":
            attachments.append(part.get_filename() or "Anhang")
        elif part.get_content_type() in ("text/plain", "text/html"):
            try:
                content = part.get_content()
            except (LookupError, ValueError):
                content = decode_text(part.get_payload(decode=True) or b"")
            if part.get_content_type() == "text/plain":
                plain.append(content)
            else:
                markup.append(html_text(content))
    lines += ["", *(plain or markup)]
    if attachments:
        lines += ["", "Anhänge: " + ", ".join(attachments)]
    return "\n".join(lines)


def _legacy_office(path, tool):
    """Alte Word-, Excel- und PowerPoint-Dateien liest catdoc."""
    if not shutil.which(tool):
        return ""
    result = subprocess.run(
        [tool, "-d", "utf-8", str(path)], capture_output=True, timeout=LEGACY_TIMEOUT_SECONDS
    )
    return result.stdout.decode("utf-8", "replace") if result.returncode == 0 else ""


def extract(path, mime_type):
    path = Path(path)
    if mime_type in ("text/html", "application/xhtml+xml"):
        text = html_text(decode_text(path.read_bytes()))
    elif mime_type in ("text/rtf", "application/rtf"):
        text = rtf_text(path.read_bytes())
    elif mime_type == "message/rfc822" or path.suffix.lower() == ".eml":
        text = mail_text(path.read_bytes())
    elif mime_type.startswith("text/") or mime_type in TEXT_TYPES:
        text = decode_text(path.read_bytes())
    elif zipfile.is_zipfile(path):
        text = _office_zip(path)
    elif tool := LEGACY_TYPES.get(mime_type) or LEGACY_TOOLS.get(path.suffix.lower()):
        text = _legacy_office(path, tool)
    else:
        text = ""
    return text[:MAX_CHARACTERS]


def safe_extract(path, mime_type):
    """Wie extract, aber eine unlesbare Datei ergibt leeren Text statt eines Fehlers."""
    try:
        return extract(path, mime_type)
    except Exception:
        return ""


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Aufruf: textextract.py DATEI DATEITYP")
    sys.stdout.buffer.write(safe_extract(sys.argv[1], sys.argv[2]).encode("utf-8"))
