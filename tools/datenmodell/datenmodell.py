"""
Erzeugt datenmodell.mmd (Mermaid) und datenmodell.dot (Graphviz) aus dem Schema einer migrierten Datenbank,
siehe docs/datenmodell.md. Aufruf: python datenmodell.py <ordner>

Der Ordner enthält die Ausgabe dieser Abfragen, jeweils mit psql -tA -F'|':

columns.txt:
    SELECT c.table_name, c.column_name,
           c.data_type || coalesce('(' || c.character_maximum_length || ')', ''), c.is_nullable,
           CASE WHEN EXISTS (SELECT 1 FROM information_schema.table_constraints tc
                             JOIN information_schema.key_column_usage k
                               ON k.constraint_name = tc.constraint_name AND k.table_name = tc.table_name
                             WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = c.table_name
                               AND k.column_name = c.column_name) THEN 'PK' ELSE '' END
    FROM information_schema.columns c
    JOIN information_schema.tables t ON t.table_name = c.table_name AND t.table_schema = c.table_schema
    WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' AND c.table_name <> 'flyway_schema_history'
    ORDER BY c.table_name, c.ordinal_position

fks.txt:
    SELECT tc.table_name, kcu.column_name, ccu.table_name, ccu.column_name, rc.delete_rule
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    JOIN information_schema.constraint_column_usage ccu
      ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
    JOIN information_schema.referential_constraints rc ON rc.constraint_name = tc.constraint_name
    WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' ORDER BY 1, 2

uniques.txt:
    SELECT tc.table_name, string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position)
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name AND tc.table_name = kcu.table_name
    WHERE tc.constraint_type = 'UNIQUE' AND tc.table_schema = 'public'
    GROUP BY tc.table_name, tc.constraint_name ORDER BY 1

Neue Tabellen müssen in AREAS einem Bereich zugeordnet werden, sonst bricht das Skript ab.
"""

import html
import sys
from collections import OrderedDict
from pathlib import Path

base = Path(sys.argv[1])

AREAS = OrderedDict([
    ("Dokumente", (["document", "document_tag", "import_rejection"], "#dbeafe", "#1d4ed8")),
    ("Stammdaten", (["category", "correspondent", "document_type", "tag"], "#dcfce7", "#15803d")),
    ("E-Mail", (["mail_account", "mail_account_tag", "mail_exclusion_rule", "processed_mail"], "#fef3c7", "#b45309")),
    ("Benutzer und Betrieb", (["app_user", "activity", "daily_report", "daily_report_excluded", "backup_settings",
                               "backup_run", "consume_folder", "storage_usage"], "#f3e8ff", "#7e22ce")),
])

DESCRIPTIONS = {
    "document": "Ein Dokument mit Original, Archivfassung, Vorschau, Text und Zuordnung",
    "document_tag": "Tags eines Dokuments (n:m)",
    "import_rejection": "Abgelehnte Importe mit Grund, bei Duplikaten mit Verweis aufs vorhandene Dokument",
    "category": "Kategorien in bis zu zehn Stufen, parent_id zeigt auf die Stufe darüber",
    "correspondent": "Absendernamen mit Regel für die automatische Zuordnung",
    "document_type": "Dokumenttypen mit Regel und optionaler Kategorie",
    "tag": "Tags mit Farbe und Regel",
    "mail_account": "Postfächer mit Zugang, Abrufregeln und Zuordnungen für neue Dokumente",
    "mail_account_tag": "Tags, die ein Postfach neuen Dokumenten gibt (n:m)",
    "mail_exclusion_rule": "Ausschlussregeln je Postfach",
    "processed_mail": "Bereits verarbeitete Nachrichten je Postfach, damit nichts doppelt kommt",
    "app_user": "Benutzer mit Rolle, Darstellung und Kontostatus",
    "activity": "Aktivitäten der Benutzer für die Übersicht",
    "daily_report": "Einstellungen der täglichen Übersichtsmail (eine Zeile)",
    "daily_report_excluded": "Superuser, die die Übersichtsmail nicht bekommen",
    "backup_settings": "Zeitplan und Ziel der Datensicherung (eine Zeile)",
    "backup_run": "Aufträge für Sicherung, Wiederherstellung und Umzug mit Fortschritt",
    "consume_folder": "Weitere Eingangsordner",
    "storage_usage": "Zuletzt gemessener Speicherbedarf (eine Zeile)",
}

TYPES = {
    "bigint": "bigint", "integer": "int", "smallint": "smallint", "boolean": "boolean", "text": "text",
    "date": "date", "tsvector": "tsvector", "jsonb": "jsonb",
    "timestamp with time zone": "timestamptz", "time without time zone": "time",
}


def short_type(raw):
    if raw.startswith("character varying"):
        return "varchar" + raw[len("character varying"):]
    if raw.startswith("ARRAY"):
        return "array"
    return TYPES.get(raw, raw.replace(" ", "_"))


columns = OrderedDict()
for line in (base / "columns.txt").read_text(encoding="utf-8").splitlines():
    if not line.strip():
        continue
    table, column, kind, nullable, key = line.split("|")
    columns.setdefault(table, []).append((column, short_type(kind), nullable == "YES", key == "PK"))

foreign = []
for line in (base / "fks.txt").read_text(encoding="utf-8").splitlines():
    if line.strip():
        table, column, target, target_column, rule = line.split("|")
        foreign.append((table, column, target, target_column, rule))
fk_columns = {(table, column) for table, column, *_ in foreign}

uniques = {}
for line in (base / "uniques.txt").read_text(encoding="utf-8").splitlines():
    if line.strip():
        table, cols = line.split("|")
        if "," not in cols:
            uniques.setdefault(table, set()).add(cols)

placed = [table for _, (tables, _, _) in AREAS.items() for table in tables]
missing = [table for table in columns if table not in placed]
if missing:
    raise SystemExit("Ohne Bereich: " + ", ".join(missing))

# --- Mermaid -----------------------------------------------------------------------------------
lines = ["erDiagram"]
for table, cols in columns.items():
    lines.append(f"    {table} {{")
    for column, kind, nullable, primary in cols:
        marks = []
        if primary:
            marks.append("PK")
        if (table, column) in fk_columns:
            marks.append("FK")
        if column in uniques.get(table, set()):
            marks.append("UK")
        kind = kind.replace("(", "_").replace(")", "")
        lines.append(f"        {kind} {column}" + (" " + ",".join(marks) if marks else ""))
    lines.append("    }")
for table, column, target, _, rule in foreign:
    many = "o{" if not any(c == column and p for c, _, _, p in columns[table]) else "o{"
    nullable = next(n for c, _, n, _ in columns[table] if c == column)
    left = "|o" if nullable else "||"
    lines.append(f'    {target} {left}--{many} {table} : "{column}"')
(base / "datenmodell.mmd").write_text("\n".join(lines) + "\n", encoding="utf-8")

# --- Graphviz ----------------------------------------------------------------------------------
dot = ['digraph documan {', '  graph [rankdir=LR, fontname="DejaVu Sans", fontsize=11, nodesep=0.35, ranksep=1.0, '
       'pad=0.3, bgcolor="white", label="DocuMan – Datenmodell (Java und Vaadin, Migration V22)", '
       'labelloc=t, fontsize=16];',
       '  node [shape=plain, fontname="DejaVu Sans", fontsize=10];',
       '  edge [fontname="DejaVu Sans", fontsize=8, color="#64748b", arrowhead=crow, arrowtail=none, dir=both];']
for area, (tables, fill, border) in AREAS.items():
    dot.append(f'  subgraph "cluster_{area}" {{ label="{area}"; style="rounded"; color="{border}"; '
               f'fontcolor="{border}"; fontsize=12;')
    for table in tables:
        rows = [f'<tr><td colspan="2" bgcolor="{border}" port="title"><font color="white"><b>{table}</b></font></td></tr>']
        for column, kind, nullable, primary in columns[table]:
            mark = '<font color="#b45309"><b>PK</b></font> ' if primary else ('<font color="#2563eb">FK</font> ' if (table, column) in fk_columns else "")
            name = f"<b>{html.escape(column)}</b>" if primary else html.escape(column)
            if column in uniques.get(table, set()):
                name += " <font color=\"#64748b\">(eindeutig)</font>"
            optional = "" if not nullable else " <font color=\"#94a3b8\">?</font>"
            rows.append(f'<tr><td align="left" port="{column}">{mark}{name}</td>'
                        f'<td align="left"><font color="#475569">{html.escape(kind)}</font>{optional}</td></tr>')
        dot.append(f'    {table} [label=<<table border="0" cellborder="1" cellspacing="0" cellpadding="4" '
                   f'bgcolor="{fill}" color="{border}">{"".join(rows)}</table>>];')
    dot.append("  }")
for table, column, target, target_column, rule in foreign:
    style = "dashed" if rule == "SET NULL" else "solid"
    dot.append(f'  {target}:{target_column} -> {table}:{column} [style={style}];')
# Tabellen ohne Beziehungen in Reihen nebeneinander statt in einer langen Spalte.
for row in (["activity", "daily_report", "backup_settings"], ["backup_run", "consume_folder", "storage_usage"]):
    for left, right in zip(row, row[1:]):
        dot.append(f"  {left} -> {right} [style=invis];")
dot.append('  legende [label=<<table border="0" cellborder="0" cellspacing="2" cellpadding="3">'
           '<tr><td align="left" colspan="2"><b>Legende</b></td></tr>'
           '<tr><td align="left"><font color="#b45309"><b>PK</b></font></td><td align="left">Primärschlüssel</td></tr>'
           '<tr><td align="left"><font color="#2563eb">FK</font></td><td align="left">Fremdschlüssel</td></tr>'
           '<tr><td align="left">?</td><td align="left">darf leer sein</td></tr>'
           '<tr><td align="left">———</td><td align="left">wird mitgelöscht (CASCADE)</td></tr>'
           '<tr><td align="left">- - -</td><td align="left">wird leer gesetzt (SET NULL)</td></tr>'
           '<tr><td align="left">Krähenfuß</td><td align="left">„viele“-Seite der Beziehung</td></tr>'
           '</table>>];')
dot.append("  storage_usage -> legende [style=invis];")
dot.append("}")
(base / "datenmodell.dot").write_text("\n".join(dot) + "\n", encoding="utf-8")
print(f"{len(columns)} Tabellen, {len(foreign)} Fremdschlüssel")
