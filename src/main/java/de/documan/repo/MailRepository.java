package de.documan.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.AfterImport;
import de.documan.domain.AttachmentFilter;
import de.documan.domain.Choice;
import de.documan.domain.MailAccount;
import de.documan.domain.MailAuth;
import de.documan.domain.MailRule;
import de.documan.domain.MailRuleField;
import de.documan.domain.MailSecurity;

@Repository
public class MailRepository {

    private final JdbcClient jdbc;

    public MailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private MailAccount map(ResultSet row) throws SQLException {
        long id = row.getLong("id");
        return new MailAccount(id, row.getString("name"), row.getBoolean("enabled"), row.getString("host"),
                row.getInt("port"), Choice.of(MailSecurity.class, row.getString("security")),
                row.getString("username"), row.getString("password_encrypted"), row.getString("folder"),
                row.getBoolean("only_unseen"), row.getInt("maximum_age_days"), row.getString("filter_from"),
                row.getString("filter_subject"), Choice.of(AttachmentFilter.class, row.getString("attachments")),
                Choice.of(AfterImport.class, row.getString("after_import")), row.getString("target_folder"),
                Rows.nullableLong(row, "assign_correspondent_id"), Rows.nullableLong(row, "assign_document_type_id"),
                tagIds(id), Rows.instant(row, "last_check_at"), row.getString("last_error"),
                row.getInt("imported_count"), Choice.of(MailAuth.class, row.getString("auth_method")),
                row.getBoolean("skip_spam"), rules(id), row.getInt("poll_minutes"));
    }

    private List<MailRule> rules(long accountId) {
        return jdbc.sql("SELECT field, value FROM mail_exclusion_rule WHERE account_id = ? ORDER BY id")
                .param(accountId)
                .query((row, index) -> new MailRule(Choice.of(MailRuleField.class, row.getString("field")),
                        row.getString("value")))
                .list();
    }

    private List<Long> tagIds(long accountId) {
        return jdbc.sql("SELECT tag_id FROM mail_account_tag WHERE mail_account_id = ? ORDER BY tag_id")
                .param(accountId).query(Long.class).list();
    }

    public List<MailAccount> all() {
        return jdbc.sql("SELECT * FROM mail_account ORDER BY name").query((row, index) -> map(row)).list();
    }

    public List<MailAccount> enabled() {
        return jdbc.sql("SELECT * FROM mail_account WHERE enabled ORDER BY name")
                .query((row, index) -> map(row)).list();
    }

    public Optional<MailAccount> find(long id) {
        return jdbc.sql("SELECT * FROM mail_account WHERE id = ?").param(id).query((row, index) -> map(row)).optional();
    }

    public long insert(MailAccount account) {
        long id = jdbc.sql("""
                INSERT INTO mail_account (name, enabled, host, port, security, username, password_encrypted, folder,
                    only_unseen, maximum_age_days, filter_from, filter_subject, attachments, after_import,
                    target_folder, assign_correspondent_id, assign_document_type_id, auth_method, skip_spam,
                    poll_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
                .params(account.name(), account.enabled(), account.host(), account.port(), account.security().value(),
                        account.username(), account.passwordEncrypted(), account.folder(), account.onlyUnseen(),
                        account.maximumAgeDays(), account.filterFrom(), account.filterSubject(),
                        account.attachments().value(), account.afterImport().value(), account.targetFolder(),
                        account.assignCorrespondentId(), account.assignDocumentTypeId(), account.auth().value(),
                        account.skipSpam(), account.pollMinutes())
                .query(Long.class).single();
        setTags(id, account.assignTagIds());
        setRules(id, account.exclusionRules());
        return id;
    }

    public void update(MailAccount account) {
        jdbc.sql("""
                UPDATE mail_account SET name = ?, enabled = ?, host = ?, port = ?, security = ?, username = ?,
                    password_encrypted = ?, folder = ?, only_unseen = ?, maximum_age_days = ?, filter_from = ?,
                    filter_subject = ?, attachments = ?, after_import = ?, target_folder = ?,
                    assign_correspondent_id = ?, assign_document_type_id = ?, auth_method = ?, skip_spam = ?,
                    poll_minutes = ?
                WHERE id = ?
                """)
                .params(account.name(), account.enabled(), account.host(), account.port(), account.security().value(),
                        account.username(), account.passwordEncrypted(), account.folder(), account.onlyUnseen(),
                        account.maximumAgeDays(), account.filterFrom(), account.filterSubject(),
                        account.attachments().value(), account.afterImport().value(), account.targetFolder(),
                        account.assignCorrespondentId(), account.assignDocumentTypeId(), account.auth().value(),
                        account.skipSpam(), account.pollMinutes(), account.id())
                .update();
        setTags(account.id(), account.assignTagIds());
        setRules(account.id(), account.exclusionRules());
    }

    private void setTags(long id, Collection<Long> tagIds) {
        jdbc.sql("DELETE FROM mail_account_tag WHERE mail_account_id = ?").param(id).update();
        for (long tagId : tagIds) {
            jdbc.sql("INSERT INTO mail_account_tag (mail_account_id, tag_id) VALUES (?, ?)").params(id, tagId).update();
        }
    }

    private void setRules(long id, Collection<MailRule> rules) {
        jdbc.sql("DELETE FROM mail_exclusion_rule WHERE account_id = ?").param(id).update();
        for (MailRule rule : rules) {
            jdbc.sql("INSERT INTO mail_exclusion_rule (account_id, field, value) VALUES (?, ?, ?)")
                    .params(id, rule.field().value(), rule.value()).update();
        }
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM mail_account WHERE id = ?").param(id).update();
    }

    /** Microsoft kann beim Abruf einen neuen Refresh-Token ausstellen. */
    public void setCredential(long id, String encrypted) {
        jdbc.sql("UPDATE mail_account SET password_encrypted = ? WHERE id = ?").params(encrypted, id).update();
    }

    public void markDue(long id) {
        jdbc.sql("UPDATE mail_account SET last_check_at = NULL WHERE id = ?").param(id).update();
    }

    public void recordCheck(long id, Instant at, String error, int imported) {
        jdbc.sql("""
                UPDATE mail_account SET last_check_at = ?, last_error = ?, imported_count = imported_count + ?,
                    poll_started_at = NULL, poll_done = 0, poll_total = 0
                WHERE id = ?
                """)
                .params(Timestamp.from(at), error, imported, id).update();
    }

    /** Stand eines Postfachs für die Fortschrittsanzeige. */
    public record PollProgress(long id, String state, int done, int total) {
    }

    public List<PollProgress> pollProgress() {
        return jdbc.sql("""
                SELECT id, poll_done, poll_total, CASE
                    WHEN poll_started_at IS NOT NULL THEN 'running'
                    WHEN enabled AND last_check_at IS NULL THEN 'waiting'
                    ELSE 'idle' END AS state
                FROM mail_account ORDER BY name
                """)
                .query((row, index) -> new PollProgress(row.getLong("id"), row.getString("state"),
                        row.getInt("poll_done"), row.getInt("poll_total")))
                .list();
    }

    public void startPoll(long id) {
        jdbc.sql("UPDATE mail_account SET poll_started_at = now(), poll_done = 0, poll_total = 0 WHERE id = ?")
                .param(id).update();
    }

    public void setPollTotal(long id, int total) {
        jdbc.sql("UPDATE mail_account SET poll_total = ? WHERE id = ?").params(total, id).update();
    }

    public void setPollDone(long id, int done) {
        jdbc.sql("UPDATE mail_account SET poll_done = ? WHERE id = ?").params(done, id).update();
    }

    public void clearPolls() {
        jdbc.sql("""
                UPDATE mail_account SET poll_started_at = NULL, poll_done = 0, poll_total = 0
                WHERE poll_started_at IS NOT NULL
                """).update();
    }

    public int disable(Long[] ids, String note) {
        return jdbc.sql("UPDATE mail_account SET enabled = false, last_error = ? WHERE id = ANY(?)")
                .params(note, ids).update();
    }

    public Set<String> knownMessages(long accountId, Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc
                .sql("SELECT message_id FROM processed_mail WHERE account_id = ? AND message_id = ANY(?)")
                .params(accountId, messageIds.toArray(String[]::new))
                .query(String.class).list());
    }

    /** Eine bereits verarbeitete Nachricht. */
    public record ProcessedMail(String messageId, String subject) {
    }

    public List<ProcessedMail> processed(long accountId) {
        return jdbc.sql("SELECT message_id, subject FROM processed_mail WHERE account_id = ? ORDER BY id")
                .param(accountId)
                .query((row, index) -> new ProcessedMail(row.getString("message_id"), row.getString("subject")))
                .list();
    }

    /** Gibt false zurück, wenn die Nachricht schon bekannt war. */
    public boolean rememberMessage(long accountId, String messageId, String subject) {
        return jdbc.sql("""
                INSERT INTO processed_mail (account_id, message_id, subject) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """)
                .params(accountId, messageId, subject).update() > 0;
    }
}
