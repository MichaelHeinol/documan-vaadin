package de.documan.service.mail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Geöffnetes Postfach. Tests ersetzen es durch eine Attrappe. */
public interface MailboxClient extends AutoCloseable {

    /** Suchbedingungen: nur Ungelesene und frühestes Datum (kann null sein). */
    record Criteria(boolean unseenOnly, java.time.LocalDate since) {
    }

    /** {@code date} ist die rohe Kopfzeile, {@code sent} ihr Zeitpunkt (null, wenn sie unlesbar ist). */
    record Header(long uid, String messageId, String date, String from, String to, String subject, Instant sent,
            boolean spam) {

        /** Nachricht ohne Spam-Kennzeichnung. */
        public Header(long uid, String messageId, String date, String from, String to, String subject, Instant sent) {
            this(uid, messageId, date, from, to, subject, sent, false);
        }
    }

    record Attachment(String filename, String disposition, byte[] payload) {
    }

    record MailMessage(Header header, List<Attachment> attachments) {
    }

    /** Nur Kopfzeilen, die neuesten Nachrichten bis zum Limit. */
    List<Header> headers(Criteria criteria, int limit) throws Exception;

    Optional<MailMessage> fetch(long uid) throws Exception;

    void markSeen(long uid) throws Exception;

    void move(long uid, String folder) throws Exception;

    void delete(long uid) throws Exception;

    List<String> folders() throws Exception;

    @Override
    void close();
}
