package de.documan.domain;

import java.time.Instant;
import java.util.List;

public record MailAccount(
        long id,
        String name,
        boolean enabled,
        String host,
        int port,
        MailSecurity security,
        String username,
        String passwordEncrypted,
        String folder,
        boolean onlyUnseen,
        int maximumAgeDays,
        String filterFrom,
        String filterSubject,
        AttachmentFilter attachments,
        AfterImport afterImport,
        String targetFolder,
        Long assignCorrespondentId,
        Long assignDocumentTypeId,
        List<Long> assignTagIds,
        Instant lastCheckAt,
        String lastError,
        int importedCount,
        MailAuth auth,
        boolean skipSpam,
        List<MailRule> exclusionRules,
        int pollMinutes) {

    public static final int DEFAULT_POLL_MINUTES = 60;
    public static final int MIN_POLL_MINUTES = 5;
    public static final int MAX_POLL_MINUTES = 10080;

    /** Postfach, das im üblichen Abstand abgerufen wird. */
    public MailAccount(long id, String name, boolean enabled, String host, int port, MailSecurity security,
            String username, String passwordEncrypted, String folder, boolean onlyUnseen, int maximumAgeDays,
            String filterFrom, String filterSubject, AttachmentFilter attachments, AfterImport afterImport,
            String targetFolder, Long assignCorrespondentId, Long assignDocumentTypeId, List<Long> assignTagIds,
            Instant lastCheckAt, String lastError, int importedCount, MailAuth auth, boolean skipSpam,
            List<MailRule> exclusionRules) {
        this(id, name, enabled, host, port, security, username, passwordEncrypted, folder, onlyUnseen, maximumAgeDays,
                filterFrom, filterSubject, attachments, afterImport, targetFolder, assignCorrespondentId,
                assignDocumentTypeId, assignTagIds, lastCheckAt, lastError, importedCount, auth, skipSpam,
                exclusionRules, DEFAULT_POLL_MINUTES);
    }

    /** Postfach mit den Vorgaben für Spam und ohne Ausschlussregeln. */
    public MailAccount(long id, String name, boolean enabled, String host, int port, MailSecurity security,
            String username, String passwordEncrypted, String folder, boolean onlyUnseen, int maximumAgeDays,
            String filterFrom, String filterSubject, AttachmentFilter attachments, AfterImport afterImport,
            String targetFolder, Long assignCorrespondentId, Long assignDocumentTypeId, List<Long> assignTagIds,
            Instant lastCheckAt, String lastError, int importedCount, MailAuth auth) {
        this(id, name, enabled, host, port, security, username, passwordEncrypted, folder, onlyUnseen, maximumAgeDays,
                filterFrom, filterSubject, attachments, afterImport, targetFolder, assignCorrespondentId,
                assignDocumentTypeId, assignTagIds, lastCheckAt, lastError, importedCount, auth, true, List.of());
    }

    /** Postfach, das sich mit einem Passwort anmeldet. */
    public MailAccount(long id, String name, boolean enabled, String host, int port, MailSecurity security,
            String username, String passwordEncrypted, String folder, boolean onlyUnseen, int maximumAgeDays,
            String filterFrom, String filterSubject, AttachmentFilter attachments, AfterImport afterImport,
            String targetFolder, Long assignCorrespondentId, Long assignDocumentTypeId, List<Long> assignTagIds,
            Instant lastCheckAt, String lastError, int importedCount) {
        this(id, name, enabled, host, port, security, username, passwordEncrypted, folder, onlyUnseen, maximumAgeDays,
                filterFrom, filterSubject, attachments, afterImport, targetFolder, assignCorrespondentId,
                assignDocumentTypeId, assignTagIds, lastCheckAt, lastError, importedCount, MailAuth.PASSWORD);
    }

    /** Neues Postfach mit den Vorgaben der Datenbank. */
    public static MailAccount blank() {
        return new MailAccount(0, "", true, "", 993, MailSecurity.SSL, "", "", "INBOX", true, 30, "", "",
                AttachmentFilter.PDF, AfterImport.MARK_SEEN, "", null, null, List.of(), null, "", 0);
    }
}
