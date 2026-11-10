package de.documan.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MasterRepository;

/** Änderungen an Dokumenten aus der Oberfläche. Jede davon hält den Suchindex aktuell. */
@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final MasterRepository master;
    private final Reindexer reindexer;
    private final MediaStore media;

    public DocumentService(DocumentRepository documents, MasterRepository master, Reindexer reindexer,
            MediaStore media) {
        this.documents = documents;
        this.master = master;
        this.reindexer = reindexer;
        this.media = media;
    }

    private Long masterId(MasterKind kind, String name) {
        return master.getOrCreate(kind, name).map(MasterItem::id).orElse(null);
    }

    /** Absendername und Typ sind Freitext: Unbekannte Namen werden neu angelegt. */
    public void update(long id, String title, String correspondentName, String documentTypeName, LocalDate date,
            String addedBy, String notes, List<Long> tagIds, String newTags) {
        documents.updateMetadata(id, title, masterId(MasterKind.CORRESPONDENT, correspondentName),
                masterId(MasterKind.DOCUMENT_TYPE, documentTypeName), date, addedBy, notes);
        Set<Long> tags = new LinkedHashSet<>(tagIds);
        for (String name : newTags.split(",")) {
            master.getOrCreate(MasterKind.TAG, name).map(MasterItem::id).ifPresent(tags::add);
        }
        documents.setTags(id, tags);
        documents.requestFileSync(new Long[] {id});
        reindexer.reindex(id);
    }

    /** Setzt die eigene Kategorie, null übernimmt die des Dokumenttyps. Die Dateien ziehen danach um. */
    public void setCategory(Long[] ids, Long categoryId) {
        documents.setCategory(ids, categoryId);
        documents.requestFileSync(ids);
        reindexer.reindex(List.of(ids));
    }

    /** Das älteste fertige Dokument im Eingang, ausgenommen das angegebene. */
    public java.util.Optional<Document> nextInInbox(long exceptId) {
        var inbox = de.documan.repo.Selection.ALL
                .and("d.inbox")
                .and("d.status = 'done'")
                .and("d.id <> ?", exceptId);
        return documents.page(inbox, "d.added, d.id", List.of(), 1, 0).stream().findFirst();
    }

    /** Dokumente in Verarbeitung bleiben erhalten. Gibt zurück, ob gelöscht wurde. */
    public boolean delete(Document document) {
        if (document.status() == DocumentStatus.PROCESSING) {
            return false;
        }
        documents.delete(document.id());
        media.deleteFiles(document);
        return true;
    }

    public int deleteAll(List<Long> ids) {
        int deleted = 0;
        for (long id : ids) {
            var document = documents.find(id);
            if (document.isPresent() && delete(document.get())) {
                deleted++;
            }
        }
        return deleted;
    }

    public void setCorrespondent(List<Long> ids, String name) {
        Long[] selected = ids.toArray(Long[]::new);
        documents.setCorrespondent(selected, masterId(MasterKind.CORRESPONDENT, name));
        documents.requestFileSync(selected);
        reindexer.reindex(ids);
    }

    public void setDocumentType(List<Long> ids, String name) {
        Long[] selected = ids.toArray(Long[]::new);
        documents.setDocumentType(selected, masterId(MasterKind.DOCUMENT_TYPE, name));
        documents.requestFileSync(selected);
        reindexer.reindex(ids);
    }

    public MasterItem addTag(List<Long> ids, String name) {
        MasterItem tag = master.getOrCreate(MasterKind.TAG, name).orElseThrow();
        for (long id : ids) {
            documents.addTags(id, List.of(tag.id()));
        }
        reindexer.reindex(ids);
        return tag;
    }

    public MasterItem removeTag(List<Long> ids, String name) {
        MasterItem tag = master.getOrCreate(MasterKind.TAG, name).orElseThrow();
        for (long id : ids) {
            documents.removeTag(id, tag.id());
        }
        reindexer.reindex(ids);
        return tag;
    }

    /** Namen von Stammdaten stehen im Suchvektor, Änderungen müssen also nachgezogen werden. */
    public void reindexMaster(MasterKind kind, long id) {
        List<Long> affected = master.documentIds(kind, id);
        reindexer.reindex(affected);
        refileIfNamed(kind, affected);
    }

    public void deleteMaster(MasterKind kind, long id) {
        List<Long> affected = master.documentIds(kind, id);
        master.delete(kind, id);
        reindexer.reindex(affected);
        refileIfNamed(kind, affected);
    }

    /** Absendername und Dokumenttyp bestimmen Ordner und Dateinamen. */
    private void refileIfNamed(MasterKind kind, List<Long> ids) {
        if (kind != MasterKind.TAG) {
            documents.requestFileSync(ids.toArray(Long[]::new));
        }
    }
}
