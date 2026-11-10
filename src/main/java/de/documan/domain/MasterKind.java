package de.documan.domain;

import java.util.Arrays;
import java.util.Optional;

/** Die Arten von Stammdaten mit ihrem Platz in Datenbank, Adresse und Filter. */
public enum MasterKind {
    CORRESPONDENT("korrespondenten", "correspondent", "correspondent",
            "Absendername", "Absendernamen",
            "SELECT id FROM document WHERE correspondent_id = %s"),
    DOCUMENT_TYPE("dokumenttypen", "document_type", "type",
            "Dokumenttyp", "Dokumenttypen",
            "SELECT id FROM document WHERE document_type_id = %s"),
    TAG("tags", "tag", "tag",
            "Tag", "Tags",
            "SELECT document_id FROM document_tag WHERE tag_id = %s"),
    /**
     * Bis zu zehn Stufen über den Dokumenttypen. Ein Dokument hat eine eigene oder die seines Typs. Zur
     * Kategorie zählen auch die Dokumente aller Unterkategorien.
     */
    CATEGORY("kategorien", "category", "category",
            "Kategorie", "Kategorien",
            "SELECT d.id FROM document d LEFT JOIN document_type t ON t.id = d.document_type_id "
                    + "WHERE COALESCE(d.category_id, t.category_id) IN (WITH RECURSIVE below(id) AS ("
                    + "SELECT c.id FROM category c WHERE c.id = %s "
                    + "UNION SELECT c.id FROM category c JOIN below ON c.parent_id = below.id) SELECT id FROM below)");

    private final String slug;
    private final String table;
    private final String filterName;
    private final String singular;
    private final String plural;
    private final String documentsOf;

    MasterKind(String slug, String table, String filterName, String singular, String plural, String documentsOf) {
        this.slug = slug;
        this.table = table;
        this.filterName = filterName;
        this.singular = singular;
        this.plural = plural;
        this.documentsOf = documentsOf;
    }

    public static Optional<MasterKind> bySlug(String slug) {
        return Arrays.stream(values()).filter(kind -> kind.slug.equals(slug)).findFirst();
    }

    public String slug() {
        return slug;
    }

    public String table() {
        return table;
    }

    public String filterName() {
        return filterName;
    }

    public String singular() {
        return singular;
    }

    public String plural() {
        return plural;
    }

    public boolean hasColor() {
        return this == TAG;
    }

    /** Kategorien kommen über den Dokumenttyp oder den Eingangsordner, sie haben keine eigenen Regeln. */
    public boolean hasRules() {
        return this != CATEGORY;
    }

    /** SQL, das die IDs der zugeordneten Dokumente liefert. */
    public String documentsOf(String idExpression) {
        return documentsOf.formatted(idExpression);
    }
}
