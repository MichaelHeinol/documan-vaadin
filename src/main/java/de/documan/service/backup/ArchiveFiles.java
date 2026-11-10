package de.documan.service.backup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.documan.service.FileNames;

/** ZIP-Bausteine, die Sicherung und Umzug teilen. */
final class ArchiveFiles {

    private ArchiveFiles() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    static void addBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** Gespeicherte Einträge brauchen Größe und Prüfsumme schon im Kopf, also vor dem Schreiben. */
    private static void markStored(ZipEntry entry, Path path) throws IOException {
        CRC32 crc = new CRC32();
        long size;
        try (InputStream file = new CheckedInputStream(Files.newInputStream(path), crc)) {
            size = file.transferTo(OutputStream.nullOutputStream());
        }
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
    }

    /** Fügt eine Datei hinzu und gibt ihre Prüfsumme zurück. Bereits Komprimiertes wird nur gespeichert. */
    static String addFile(ZipOutputStream zip, Path path, String name, Set<String> storedSuffixes) throws IOException {
        // Zuerst öffnen: Fehlt die Datei, darf noch kein Eintrag begonnen sein.
        try (InputStream file = Files.newInputStream(path)) {
            MessageDigest digest = sha256();
            ZipEntry entry = new ZipEntry(name);
            entry.setLastModifiedTime(Files.getLastModifiedTime(path));
            if (storedSuffixes.contains(FileNames.suffix(name).toLowerCase(Locale.ROOT))) {
                markStored(entry, path);
            }
            zip.putNextEntry(entry);
            new DigestInputStream(file, digest).transferTo(zip);
            zip.closeEntry();
            return hex(digest);
        }
    }

    /** Schreibt einen Eintrag in eine neue Datei und gibt dessen Prüfsumme zurück. */
    static String extract(InputStream source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        MessageDigest digest = sha256();
        try (OutputStream output = new DigestOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)), digest)) {
            source.transferTo(output);
        }
        return hex(digest);
    }
}
