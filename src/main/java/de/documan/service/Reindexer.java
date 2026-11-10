package de.documan.service;

import java.util.Collection;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Schreibt den Suchvektor neu. Nach jeder Änderung an Text oder Metadaten aufrufen. */
@Component
public class Reindexer {

    public static final String SEARCH_CONFIG = "german";
    // to_tsvector bricht ab, wenn der Vektor 1 MB übersteigt.
    private static final int SEARCH_CONTENT_LIMIT = 500_000;
    private static final int SEARCH_CONTENT_FALLBACK = 100_000;
    // Eine Adresse wäre sonst nur als Ganzes ein Suchwort, das translate macht auch die Domain findbar.
    private static final String UPDATE = """
            UPDATE document d SET search_vector =
                setweight(to_tsvector('german', d.title), 'A')
                || setweight(to_tsvector('german', concat_ws(' ',
                       (SELECT name FROM correspondent WHERE id = d.correspondent_id),
                       (SELECT name FROM document_type WHERE id = d.document_type_id),
                       (WITH RECURSIVE up(id, parent_id, name, depth) AS (
                            SELECT c.id, c.parent_id, c.name, 0 FROM category c
                            WHERE c.id = COALESCE(d.category_id,
                                (SELECT category_id FROM document_type WHERE id = d.document_type_id))
                            UNION ALL SELECT c.id, c.parent_id, c.name, up.depth + 1 FROM category c
                            JOIN up ON c.id = up.parent_id WHERE up.depth < 20)
                        SELECT string_agg(name, ' ' ORDER BY depth DESC) FROM up),
                       (SELECT string_agg(t.name, ' ') FROM document_tag link
                        JOIN tag t ON t.id = link.tag_id WHERE link.document_id = d.id),
                       d.mail_from, translate(d.mail_from, '<>@.', '    '), d.mail_subject)), 'B')
                || setweight(to_tsvector('german', d.notes), 'B')
                || setweight(to_tsvector('german', left(d.content, ?)), 'C')
            WHERE d.id = ?
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate savepoint;

    public Reindexer(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public void reindex(long id) {
        try {
            savepoint.executeWithoutResult(status -> update(id, SEARCH_CONTENT_LIMIT));
        } catch (DataAccessException tooLong) {
            update(id, SEARCH_CONTENT_FALLBACK);
        }
    }

    public void reindex(Collection<Long> ids) {
        ids.forEach(this::reindex);
    }

    private void update(long id, int limit) {
        jdbc.sql(UPDATE).params(limit, id).update();
    }
}
