package de.documan.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.Algorithm;
import de.documan.domain.Choice;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;

/** Absendernamen, Dokumenttypen, Tags und Kategorien teilen sich Aufbau und Abfragen. */
@Repository
public class MasterRepository {

    private final JdbcClient jdbc;

    public MasterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String columns(MasterKind kind) {
        String color = kind.hasColor() ? "m.color" : "NULL";
        return "m.id, m.name, m.algorithm, m.match_text, m.ignore_case, " + color + " AS color, "
                + "(SELECT count(*) FROM (" + kind.documentsOf("m.id") + ") linked) AS document_count";
    }

    private static MasterItem map(ResultSet row) throws SQLException {
        return new MasterItem(row.getLong("id"), row.getString("name"),
                Choice.of(Algorithm.class, row.getString("algorithm")), row.getString("match_text"),
                row.getBoolean("ignore_case"), row.getString("color"), row.getLong("document_count"));
    }

    public List<MasterItem> all(MasterKind kind) {
        return jdbc.sql("SELECT " + columns(kind) + " FROM " + kind.table() + " m ORDER BY m.name")
                .query((row, index) -> map(row)).list();
    }

    public List<MasterItem> withIds(MasterKind kind, Long[] ids) {
        return jdbc.sql("SELECT " + columns(kind) + " FROM " + kind.table() + " m WHERE m.id = ANY(?) ORDER BY m.name")
                .param(ids)
                .query((row, index) -> map(row)).list();
    }

    /** Einträge, die mindestens einem Dokument zugeordnet sind, die häufigsten zuerst. */
    public List<MasterItem> ranked(MasterKind kind, int limit) {
        return jdbc.sql("SELECT * FROM (SELECT " + columns(kind) + " FROM " + kind.table() + " m) ranked "
                + "WHERE document_count > 0 ORDER BY document_count DESC, name LIMIT ?")
                .param(limit)
                .query((row, index) -> map(row)).list();
    }

    /** Einträge mit Regel für die automatische Zuordnung. */
    public List<MasterItem> withRules(MasterKind kind) {
        return all(kind).stream().filter(MasterItem::hasRule).toList();
    }

    public Optional<MasterItem> find(MasterKind kind, long id) {
        return jdbc.sql("SELECT " + columns(kind) + " FROM " + kind.table() + " m WHERE m.id = ?")
                .param(id)
                .query((row, index) -> map(row)).optional();
    }

    public long count(MasterKind kind) {
        return jdbc.sql("SELECT count(*) FROM " + kind.table()).query(Long.class).single();
    }

    public long insert(MasterKind kind, String name, Algorithm algorithm, String match, boolean ignoreCase,
            String color) {
        if (kind.hasColor()) {
            return jdbc.sql("INSERT INTO tag (name, algorithm, match_text, ignore_case, color) "
                    + "VALUES (?, ?, ?, ?, ?) RETURNING id")
                    .params(name, algorithm.value(), match, ignoreCase, color)
                    .query(Long.class).single();
        }
        return jdbc.sql("INSERT INTO " + kind.table() + " (name, algorithm, match_text, ignore_case) "
                + "VALUES (?, ?, ?, ?) RETURNING id")
                .params(name, algorithm.value(), match, ignoreCase)
                .query(Long.class).single();
    }

    public void update(MasterKind kind, long id, String name, Algorithm algorithm, String match, boolean ignoreCase,
            String color) {
        if (kind.hasColor()) {
            jdbc.sql("UPDATE tag SET name = ?, algorithm = ?, match_text = ?, ignore_case = ?, color = ? WHERE id = ?")
                    .params(name, algorithm.value(), match, ignoreCase, color, id).update();
        } else {
            jdbc.sql("UPDATE " + kind.table()
                    + " SET name = ?, algorithm = ?, match_text = ?, ignore_case = ? WHERE id = ?")
                    .params(name, algorithm.value(), match, ignoreCase, id).update();
        }
    }

    public void delete(MasterKind kind, long id) {
        if (kind == MasterKind.CATEGORY) {
            deleteCategory(id);
            return;
        }
        jdbc.sql("DELETE FROM " + kind.table() + " WHERE id = ?").param(id).update();
    }

    public List<Long> documentIds(MasterKind kind, long id) {
        return jdbc.sql(kind.documentsOf("?")).param(id).query(Long.class).list();
    }

    /** Eine Kategorie mit ihrer übergeordneten. */
    public record CategoryNode(long id, String name, Long parentId) {
    }

    /** Alle Kategorien. Es sind wenige, Pfade und Summen rechnet CategoryPaths. */
    public List<CategoryNode> categories() {
        return jdbc.sql("SELECT id, name, parent_id FROM category ORDER BY name")
                .query((row, index) -> new CategoryNode(row.getLong("id"), row.getString("name"),
                        Rows.nullableLong(row, "parent_id")))
                .list();
    }

    /** Legt eine Kategorie an (id null) oder ändert Name und übergeordnete Kategorie. Gibt die ID zurück. */
    public long saveCategory(Long id, String name, Long parentId) {
        if (id == null) {
            return jdbc.sql("INSERT INTO category (name, parent_id) VALUES (?, ?) RETURNING id").params(name, parentId)
                    .query(Long.class).single();
        }
        jdbc.sql("UPDATE category SET name = ?, parent_id = ? WHERE id = ?").params(name, parentId, id).update();
        return id;
    }

    /**
     * Die Kategorie zu einem Pfad wie Finanzen, Bank. Fehlende Stufen werden angelegt, vorhandene
     * unabhängig von Groß- und Kleinschreibung gefunden. Leer heißt: keine Kategorie.
     */
    public Optional<Long> categoryChain(List<String> names) {
        Long parent = null;
        for (String raw : names) {
            String name = raw == null ? "" : raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            String shortened = name.length() > 200 ? name.substring(0, 200) : name;
            Long current = parent;
            Optional<Long> existing = jdbc.sql("""
                    SELECT id FROM category WHERE parent_id IS NOT DISTINCT FROM ? AND upper(name) = upper(?)
                    ORDER BY id LIMIT 1
                    """)
                    .params(current, shortened).query(Long.class).optional();
            parent = existing.orElseGet(() -> jdbc.sql("""
                    INSERT INTO category (name, parent_id) VALUES (?, ?)
                    ON CONFLICT (parent_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id
                    """)
                    .params(shortened, current).query(Long.class).single());
        }
        return Optional.ofNullable(parent);
    }

    /**
     * Löscht eine Kategorie. Unterkategorien, Dokumenttypen und Dokumente rücken eine Stufe nach oben, so
     * bleibt ihre übrige Einordnung erhalten.
     */
    public void deleteCategory(long id) {
        Long parent = jdbc.sql("SELECT parent_id FROM category WHERE id = ?").param(id)
                .query((row, index) -> Rows.nullableLong(row, "parent_id")).optional().orElse(null);
        jdbc.sql("UPDATE category SET parent_id = ? WHERE parent_id = ?").params(parent, id).update();
        jdbc.sql("UPDATE document_type SET category_id = ? WHERE category_id = ?").params(parent, id).update();
        jdbc.sql("UPDATE document SET category_id = ? WHERE category_id = ?").params(parent, id).update();
        jdbc.sql("DELETE FROM category WHERE id = ?").param(id).update();
    }

    /** Die Kategorie eines Dokumenttyps, leer ohne. */
    public Optional<Long> categoryOf(long typeId) {
        return jdbc.sql("SELECT category_id FROM document_type WHERE id = ?").param(typeId)
                .query((row, index) -> Optional.ofNullable(Rows.nullableLong(row, "category_id"))).optional()
                .flatMap(found -> found);
    }

    /** Ordnet einen Dokumenttyp einer Kategorie zu. null nimmt ihn heraus. */
    public void setCategory(long typeId, Long categoryId) {
        jdbc.sql("UPDATE document_type SET category_id = ? WHERE id = ?").params(categoryId, typeId).update();
    }

    /** Kategorie je Dokumenttyp, nur für Typen mit Kategorie. */
    public Map<Long, Long> typeCategories() {
        Map<Long, Long> categories = new LinkedHashMap<>();
        jdbc.sql("SELECT id, category_id FROM document_type WHERE category_id IS NOT NULL")
                .query(row -> {
                    categories.put(row.getLong("id"), row.getLong("category_id"));
                });
        return categories;
    }

    /** Die Dokumenttypen je Kategorie, nach Namen sortiert. */
    public Map<Long, List<String>> categoryTypes() {
        Map<Long, List<String>> types = new LinkedHashMap<>();
        jdbc.sql("SELECT category_id, name FROM document_type WHERE category_id IS NOT NULL ORDER BY name")
                .query(row -> {
                    types.computeIfAbsent(row.getLong("category_id"), key -> new ArrayList<>())
                            .add(row.getString("name"));
                });
        return types;
    }

    /** Leer bedeutet: keine Zuordnung. Groß- und Kleinschreibung spielen keine Rolle. */
    public Optional<MasterItem> getOrCreate(MasterKind kind, String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        String shortened = cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
        Optional<MasterItem> existing = jdbc
                .sql("SELECT " + columns(kind) + " FROM " + kind.table() + " m WHERE upper(m.name) = upper(?) "
                        + "ORDER BY m.id LIMIT 1")
                .param(shortened)
                .query((row, index) -> map(row)).optional();
        if (existing.isPresent()) {
            return existing;
        }
        long id = insert(kind, shortened, Algorithm.NONE, "", true, MasterItem.DEFAULT_COLOR);
        return find(kind, id);
    }
}
