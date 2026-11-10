"""Erzeugt DocuMan-Anleitung.pdf aus der README.md, damit beide nie auseinanderlaufen."""

import datetime
import html
import re
from pathlib import Path

from markdown_it import MarkdownIt
from weasyprint import HTML

SOURCE = Path("README.md")
TARGET = Path("DocuMan-Anleitung.pdf")

STYLE = """
@page {
  size: A4;
  margin: 22mm 20mm 20mm;
  @bottom-left { content: "DocuMan-Anleitung"; color: #7c8491; font: 8.5pt "Liberation Sans", sans-serif; }
  @bottom-right {
    content: "Seite " counter(page) " von " counter(pages);
    color: #7c8491; font: 8.5pt "Liberation Sans", sans-serif;
  }
}
@page :first { @bottom-left { content: none; } }

body { color: #14171c; font: 10.5pt/1.5 "Liberation Sans", "DejaVu Sans", sans-serif; }
h1, h2, h3 { line-height: 1.25; break-after: avoid; }
h1 { margin: 0 0 4pt; color: #0b7a6e; font-size: 27pt; }
h2 { margin: 22pt 0 7pt; padding-bottom: 3pt; border-bottom: 1pt solid #e3e6eb; font-size: 16pt; }
h3 { margin: 15pt 0 5pt; font-size: 12pt; }
p, ul, ol { margin: 0 0 7pt; }
ul, ol { padding-left: 16pt; }
li { margin-bottom: 3pt; }
li > ul, li > ol, li > pre { margin-top: 4pt; }
a { color: #0b7a6e; text-decoration: none; }
strong { font-weight: 700; }

code {
  padding: 0 2pt; border-radius: 2pt; background: #eef0f4;
  font: 9.2pt "Liberation Mono", "DejaVu Sans Mono", monospace;
}
pre {
  margin: 0 0 8pt; padding: 7pt 9pt; border: .6pt solid #e3e6eb; border-radius: 4pt;
  background: #f5f6f8; white-space: pre-wrap; overflow-wrap: anywhere; break-inside: avoid;
}
pre code { padding: 0; background: none; font-size: 8.8pt; }

table { width: 100%; margin: 0 0 9pt; border-collapse: collapse; break-inside: avoid; }
th { padding: 4pt 7pt; border-bottom: 1pt solid #c9ced6; color: #5c6470; font-size: 9pt; text-align: left; }
td { padding: 4pt 7pt; border-bottom: .6pt solid #e3e6eb; vertical-align: top; }

.stand { margin-bottom: 14pt; color: #5c6470; font-size: 9.5pt; }
.inhalt { margin: 14pt 0 0; padding: 10pt 13pt; border-radius: 4pt; background: #f5f6f8; break-inside: avoid; }
.inhalt b { display: block; margin-bottom: 5pt; }
.inhalt a { display: block; padding: 1.5pt 0; color: inherit; }
.inhalt a.sub { padding-left: 12pt; color: #5c6470; font-size: 9.5pt; }
.inhalt a::after { content: target-counter(attr(href), page); float: right; }
"""


def add_anchors(body):
    """Versieht Überschriften mit Sprungzielen und baut daraus das Inhaltsverzeichnis."""
    entries = []

    def anchor(match):
        level, title = match.group(1), match.group(2)
        target = f"kapitel-{len(entries) + 1}"
        css = ' class="sub"' if level == "3" else ""
        entries.append(f'<a{css} href="#{target}">{title}</a>')
        return f'<h{level} id="{target}">{title}</h{level}>'

    body = re.sub(r"<h([23])>(.*?)</h\1>", anchor, body)
    return body, f'<nav class="inhalt"><b>Inhalt</b>{"".join(entries)}</nav>'


def main():
    renderer = MarkdownIt("commonmark").enable("table")
    body, contents = add_anchors(renderer.render(SOURCE.read_text(encoding="utf-8")))

    stamp = datetime.date.today().strftime("%d.%m.%Y")
    title_end = body.index("</h1>") + len("</h1>")
    first_section = body.index("<h2")
    body = (
        body[:title_end]
        + f'<p class="stand">Anleitung, Stand {stamp}</p>'
        + body[title_end:first_section]
        + contents
        + body[first_section:]
    )

    page = (
        f'<!doctype html><html lang="de"><head><meta charset="utf-8">'
        f"<title>{html.escape('DocuMan-Anleitung')}</title>"
        f"<style>{STYLE}</style></head><body>{body}</body></html>"
    )
    HTML(string=page).write_pdf(TARGET)
    print(f"{TARGET} geschrieben")


if __name__ == "__main__":
    main()
