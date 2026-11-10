package de.documan.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import de.documan.service.FileNames;

/** Ein Dokument. In Listen bleibt {@code content} leer, um große Texte nicht mitzuladen. */
public record Document(
        long id,
        String title,
        Long correspondentId,
        String correspondentName,
        Long documentTypeId,
        String documentTypeName,
        Long categoryId,
        String categoryName,
        Long ownCategoryId,
        LocalDate documentDate,
        String notes,
        boolean inbox,
        String content,
        Integer pageCount,
        String original,
        String archive,
        String thumbnail,
        String originalFilename,
        String mimeType,
        long size,
        String checksum,
        DocumentSource source,
        String sourceDetail,
        String addedBy,
        MailHeaders mail,
        DocumentStatus status,
        String error,
        int attempts,
        Instant added,
        Instant modified,
        List<MasterItem> tags,
        String snippet) {

    /** Daraus entsteht ein durchsuchbares PDF, das Original bleibt daneben unverändert. */
    public static final Set<String> OCR_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/tiff", "image/webp");
    /** Nur diese Bilder zeigt der Browser direkt. SVG gehört nicht dazu, es kann Skripte enthalten. */
    public static final Set<String> PREVIEW_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    /** Werden unverändert als Text gelesen, alle anderen Formate liest textextract. */
    public static final Set<String> PLAIN_TEXT_TYPES = Set.of("text/plain", "text/csv", "text/markdown");

    public Document withTags(List<MasterItem> newTags) {
        return new Document(id, title, correspondentId, correspondentName, documentTypeId, documentTypeName,
                categoryId, categoryName, ownCategoryId, documentDate, notes, inbox, content, pageCount, original,
                archive, thumbnail, originalFilename, mimeType, size, checksum, source, sourceDetail, addedBy, mail,
                status, error, attempts, added, modified, newTags, snippet);
    }

    public Document withSnippet(String newSnippet) {
        return new Document(id, title, correspondentId, correspondentName, documentTypeId, documentTypeName,
                categoryId, categoryName, ownCategoryId, documentDate, notes, inbox, content, pageCount, original,
                archive, thumbnail, originalFilename, mimeType, size, checksum, source, sourceDetail, addedBy, mail,
                status, error, attempts, added, modified, tags, newSnippet);
    }

    /** Archiv und Vorschau liegen im selben Ordner wie das Original. */
    public String originalFolder() {
        Path folder = Path.of("originals").relativize(Path.of(original)).getParent();
        return folder == null ? "" : folder.toString().replace('\\', '/');
    }

    public boolean hasArchive() {
        return !archive.isEmpty();
    }

    public boolean hasThumbnail() {
        return !thumbnail.isEmpty();
    }

    public boolean isPdf() {
        return "application/pdf".equals(mimeType);
    }

    public boolean isText() {
        return PLAIN_TEXT_TYPES.contains(mimeType);
    }

    public boolean isOcrImage() {
        return OCR_IMAGE_TYPES.contains(mimeType);
    }

    public boolean isFinished() {
        return status == DocumentStatus.DONE || status == DocumentStatus.FAILED;
    }

    public boolean isFailed() {
        return status == DocumentStatus.FAILED;
    }

    public boolean isDone() {
        return status == DocumentStatus.DONE;
    }

    /** pdf, image, text oder none. Andere Formate öffnet der Browser nie direkt. */
    public String previewKind() {
        if (hasArchive() || isPdf()) {
            return "pdf";
        }
        if (PREVIEW_IMAGE_TYPES.contains(mimeType)) {
            return "image";
        }
        return isText() || !content.isEmpty() ? "text" : "none";
    }

    public String fileLabel() {
        String suffix = FileNames.suffix(originalFilename);
        return suffix.isEmpty() ? "DATEI" : suffix.substring(1).toUpperCase(Locale.ROOT);
    }

    public String subtitle() {
        return Stream.of(correspondentName, documentTypeName, "Ohne Absendername")
                .filter(Objects::nonNull).findFirst().orElseThrow();
    }

    public long version() {
        return modified.getEpochSecond();
    }

}
