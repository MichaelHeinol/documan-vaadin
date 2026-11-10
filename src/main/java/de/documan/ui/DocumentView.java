package de.documan.ui;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.DescriptionList;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.ActivityKind;
import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.ActivityRepository;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MasterRepository;
import de.documan.service.CategoryPaths;
import de.documan.service.DocumentService;
import de.documan.service.Filters;
import de.documan.web.Fmt;
import jakarta.annotation.security.PermitAll;

/** Ein Dokument: Vorschau links, Angaben rechts. */
@Route(value = "dokumente/:id([0-9]{1,18})", layout = MainLayout.class)
@PermitAll
public class DocumentView extends Div implements BeforeEnterObserver, HasDynamicTitle, Refreshable {

    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_ADDED_BY_LENGTH = 150;

    private final DocumentRepository documents;
    private final DocumentService service;
    private final MasterRepository master;
    private final Filters filters;
    private final ActivityRepository activities;
    private final Fmt fmt;
    private final boolean admin;
    private final String username;

    private Document document;
    private boolean edited;
    private TextField title;
    private ComboBox<String> correspondent;
    private ComboBox<String> documentType;
    private ComboBox<String> category;
    private DatePicker date;
    private MultiSelectComboBox<MasterItem> tags;
    private TextField newTags;
    private ComboBox<String> addedBy;
    private TextArea notes;

    public DocumentView(DocumentRepository documents, DocumentService service, MasterRepository master,
            Filters filters, ActivityRepository activities, Fmt fmt) {
        this.documents = documents;
        this.service = service;
        this.master = master;
        this.filters = filters;
        this.activities = activities;
        this.fmt = fmt;
        this.admin = Ui.isAdmin();
        this.username = Ui.username();
        setClassName("detail");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        long id = Long.parseLong(event.getRouteParameters().get("id").orElse("0"));
        Optional<Document> found = documents.find(id);
        if (found.isEmpty()) {
            Ui.warning("Dieses Dokument gibt es nicht mehr.");
            event.forwardTo(DocumentsView.class);
            return;
        }
        document = found.get();
        render();
    }

    @Override
    public String getPageTitle() {
        return (document == null ? "Dokument" : document.title()) + " · DocuMan";
    }

    @Override
    public boolean busy() {
        return edited;
    }

    /** Nach der Verarbeitung stehen Text und Vorschau da, solange niemand etwas geändert hat. */
    @Override
    public void refresh() {
        documents.find(document.id()).ifPresentOrElse(fresh -> {
            if (fresh.version() != document.version() || fresh.status() != document.status()) {
                document = fresh;
                render();
            }
        }, () -> UI.getCurrent().navigate(DocumentsView.class));
    }

    private void render() {
        removeAll();
        edited = false;
        add(preview(), side());
        UI.getCurrent().getPage().setTitle(getPageTitle());
    }

    private Section preview() {
        Section preview = new Section();
        preview.setClassName("detail-preview");
        preview.getElement().setAttribute("aria-label", "Vorschau");
        String file = "dokumente/" + document.id() + "/vorschau?v=" + document.version();
        switch (document.previewKind()) {
            case "pdf" -> {
                IFrame frame = new IFrame(file + "#navpanes=0&view=FitH");
                frame.setTitle("Vorschau von " + document.title());
                preview.add(frame);
            }
            case "text" -> preview.add(new Pre(document.content()));
            case "image" -> {
                Image image = new Image(file, document.title());
                preview.add(Ui.div("image-preview", image));
            }
            default -> {
                Anchor download = Ui.file("dokumente/" + document.id() + "/download/original", Ui.icon("download"),
                        new Span("Herunterladen"));
                download.setClassName("button");
                preview.add(Ui.div("no-preview", Ui.span("no-preview-badge", document.fileLabel()),
                        Ui.paragraph("", "Für dieses Format gibt es keine Vorschau. Die Datei liegt unverändert im Archiv."),
                        download));
            }
        }
        return preview;
    }

    private Div side() {
        Div side = Ui.div("detail-side");
        Anchor back = Ui.link(document.inbox() ? "dokumente?view=inbox" : "dokumente", Ui.icon("arrow-left"),
                new Span(document.inbox() ? "Zum Eingang" : "Zu den Dokumenten"));
        back.setClassName("back-link");
        side.add(back, new H1(document.title()));

        if (document.isFailed()) {
            Div note = Ui.div("status-note failed");
            note.add(new Span(Ui.span("strong", "Verarbeitung fehlgeschlagen."),
                    new Span(" " + document.error())));
            note.add(Ui.paragraph("", "Ergänzen Sie die Angaben selbst und übernehmen Sie das Dokument, oder lassen Sie es "
                    + "erneut verarbeiten."));
            note.add(Ui.secondary("Erneut verarbeiten", this::reprocess));
            side.add(note);
        } else if (!document.isFinished()) {
            side.add(Ui.div("status-note",
                    new Span(document.status().label() + ": Text und Vorschau erscheinen automatisch.")));
        }

        side.add(form());

        if (!document.previewKind().equals("text") && !document.content().isEmpty()) {
            Details text = new Details("Erkannter Text", new Pre(document.content()));
            text.addClassName("fold");
            side.add(text);
        }
        side.add(fileDetails());
        return side;
    }

    private Div form() {
        CategoryPaths paths = new CategoryPaths(master.categories());
        Long typeCategory = document.documentTypeId() == null ? null
                : master.categoryOf(document.documentTypeId()).orElse(null);
        String typeCategoryPath = paths.label(typeCategory);
        String categoryPath = paths.label(document.categoryId());

        title = new TextField("Titel");
        title.setMaxLength(MAX_TITLE_LENGTH);
        title.setRequired(true);
        title.setValue(document.title());

        correspondent = freeText("Absendername", names(MasterKind.CORRESPONDENT), document.correspondentName());
        documentType = freeText("Dokumenttyp", names(MasterKind.DOCUMENT_TYPE), document.documentTypeName());
        category = freeText("Kategorie", paths.ordered().stream().map(Map.Entry::getValue).toList(),
                paths.label(document.ownCategoryId()));
        category.setPlaceholder(typeCategoryPath.isEmpty() ? "Keine" : "Wie Dokumenttyp: " + typeCategoryPath);
        category.setHelperText("Stufen mit / trennen, etwa Finanzen / Bank. Neue Stufen werden angelegt, leer gilt die "
                + "Kategorie des Dokumenttyps." + (categoryPath.isEmpty() ? "" : " Abgelegt unter " + categoryPath + "."));

        date = Dates.picker("Dokumentdatum");
        date.setValue(document.documentDate());

        tags = new MultiSelectComboBox<>("Tags");
        List<MasterItem> allTags = master.all(MasterKind.TAG);
        tags.setItems(allTags);
        tags.setItemLabelGenerator(MasterItem::name);
        tags.select(allTags.stream().filter(tag -> document.tags().stream().anyMatch(own -> own.id() == tag.id()))
                .toList());
        newTags = new TextField("Neue Tags");
        newTags.setPlaceholder("kommagetrennt");
        newTags.setMaxLength(500);

        addedBy = freeText("Eingespielt von", filters.knownAdders(), document.addedBy());
        addedBy.setPlaceholder("Unbekannt");
        notes = new TextArea("Notizen");
        notes.setValue(document.notes());
        notes.setMinRows(3);

        List<HasValue<?, ?>> fields = List.of(title, correspondent, documentType, category, date, tags, newTags,
                addedBy, notes);
        fields.forEach(field -> field.addValueChangeListener(change -> {
            if (change.isFromClient()) {
                edited = true;
            }
        }));

        Div actions = Ui.div("form-actions");
        if (document.isFailed()) {
            actions.add(Ui.primary("Speichern und als Dokument übernehmen", () -> save("accept")),
                    Ui.secondary("Speichern", () -> save("")));
        } else if (document.inbox()) {
            actions.add(Ui.primary("Ablegen und weiter", () -> save("done")), Ui.secondary("Speichern", () -> save("")));
        } else {
            actions.add(Ui.primary("Speichern", () -> save("")));
        }
        return Ui.div("form-stack", field(title), Ui.div("form-row", field(correspondent), field(documentType)),
                field(category), field(date), field(tags), field(newTags), field(addedBy), field(notes), actions);
    }

    static Div field(Component input) {
        return Ui.div("field", input);
    }

    /** Freitext mit Vorschlägen: vorhandene Namen werden angeboten, neue angelegt. */
    static ComboBox<String> freeText(String label, List<String> suggestions, String value) {
        ComboBox<String> box = new ComboBox<>(label);
        List<String> items = new ArrayList<>(suggestions);
        if (value != null && !value.isEmpty() && !items.contains(value)) {
            items.add(value);
        }
        box.setItems(items);
        box.setAllowCustomValue(true);
        box.addCustomValueSetListener(custom -> {
            String typed = custom.getDetail();
            if (!items.contains(typed)) {
                items.add(typed);
                box.setItems(items);
            }
            box.setValue(typed);
        });
        box.setClearButtonVisible(true);
        if (value != null && !value.isEmpty()) {
            box.setValue(value);
        }
        return box;
    }

    private List<String> names(MasterKind kind) {
        return master.all(kind).stream().map(MasterItem::name).toList();
    }

    private static String text(HasValue<?, String> field) {
        return field.getValue() == null ? "" : field.getValue().strip();
    }

    private void save(String action) {
        boolean valid = true;
        String newTitle = text(title);
        title.setInvalid(false);
        if (newTitle.isEmpty()) {
            title.setErrorMessage("Bitte einen Titel angeben.");
            title.setInvalid(true);
            valid = false;
        }
        if (date.isInvalid()) {
            date.setErrorMessage("Bitte ein gültiges Datum eingeben.");
            valid = false;
        }
        String adder = text(addedBy);
        addedBy.setInvalid(false);
        if (adder.length() > MAX_ADDED_BY_LENGTH) {
            addedBy.setErrorMessage("Der Wert darf höchstens " + MAX_ADDED_BY_LENGTH + " Zeichen lang sein.");
            addedBy.setInvalid(true);
            valid = false;
        }
        // Unbekannte Stufen werden wie beim Dokumenttyp neu angelegt.
        List<String> categoryPath = CategoryPaths.parse(text(category));
        category.setInvalid(false);
        if (categoryPath.size() > CategoryPaths.MAX_LEVELS) {
            category.setErrorMessage("Höchstens " + CategoryPaths.MAX_LEVELS + " Stufen, getrennt durch /.");
            category.setInvalid(true);
            valid = false;
        }
        if (!valid) {
            return;
        }
        Long categoryId = master.categoryChain(categoryPath).orElse(null);
        long id = document.id();
        LocalDate day = date.getValue();
        List<Long> tagIds = tags.getSelectedItems().stream().map(MasterItem::id).toList();
        service.update(id, newTitle, text(correspondent), text(documentType), day, adder, notes.getValue(), tagIds,
                text(newTags));
        if (!Objects.equals(categoryId, document.ownCategoryId())) {
            service.setCategory(new Long[] {id}, categoryId);
        }
        edited = false;
        switch (action) {
            case "accept" -> {
                // Nach manueller Bearbeitung gilt das Dokument als fertig und zählt nicht mehr als Fehler.
                if (documents.acceptFailed(new Long[] {id}) > 0) {
                    activities.record(username, ActivityKind.FILED, 1);
                    Ui.success("Übernommen. Das Dokument zählt nicht mehr als Fehler.");
                } else {
                    Ui.warning("Gespeichert. Das Dokument war nicht mehr fehlgeschlagen.");
                }
                reload(id);
            }
            case "done" -> {
                activities.record(username, ActivityKind.FILED, documents.setFiled(new Long[] {id}));
                var next = service.nextInInbox(id);
                if (next.isPresent()) {
                    Ui.success("Abgelegt. Weiter mit dem nächsten Dokument.");
                    UI.getCurrent().navigate("dokumente/" + next.get().id());
                } else {
                    Ui.success("Abgelegt. Der Eingang ist leer.");
                    UI.getCurrent().navigate(DocumentsView.class);
                }
            }
            default -> {
                activities.record(username, ActivityKind.EDITED, 1);
                Ui.success("Gespeichert.");
                reload(id);
            }
        }
        MainLayout.current().ifPresent(MainLayout::refreshNow);
    }

    private void reload(long id) {
        documents.find(id).ifPresent(fresh -> {
            document = fresh;
            render();
        });
    }

    private void reprocess() {
        if (documents.requeue(new Long[] {document.id()}) > 0) {
            Ui.info("Das Dokument wird erneut verarbeitet.");
        } else {
            Ui.warning("Das Dokument wird gerade schon verarbeitet.");
        }
        reload(document.id());
        MainLayout.current().ifPresent(MainLayout::refreshNow);
    }

    private Details fileDetails() {
        DescriptionList facts = new DescriptionList();
        fact(facts, "Dateiname", document.originalFilename());
        fact(facts, "Größe", fmt.size(document.size()));
        if (document.pageCount() != null) {
            fact(facts, "Seiten", String.valueOf(document.pageCount()));
        }
        fact(facts, "Quelle", document.source().label());
        if (!document.sourceDetail().isEmpty()) {
            fact(facts, "Herkunft", document.sourceDetail());
        }
        if (!document.mail().from().isEmpty()) {
            fact(facts, "Absender", document.mail().from());
        }
        if (!document.mail().to().isEmpty()) {
            fact(facts, "Empfänger", document.mail().to());
        }
        if (!document.mail().subject().isEmpty()) {
            fact(facts, "Betreff", document.mail().subject());
        }
        if (document.mail().date() != null) {
            fact(facts, "Gesendet", fmt.dateTime(document.mail().date()));
        }
        fact(facts, "Hinzugefügt", fmt.dateTime(document.added()));
        fact(facts, "Prüfsumme", fmt.checksum(document.checksum())).addClassName("mono");

        Div actions = Ui.div("actions");
        Anchor original = Ui.file("dokumente/" + document.id() + "/download/original", Ui.icon("download"),
                new Span("Original"));
        original.setClassName("button");
        actions.add(original);
        if (document.hasArchive()) {
            Anchor archive = Ui.file("dokumente/" + document.id() + "/download/archiv", Ui.icon("download"),
                    new Span("Archiv-PDF"));
            archive.setClassName("button");
            actions.add(archive);
        }
        if (document.previewKind().equals("pdf")) {
            Anchor open = Ui.file("dokumente/" + document.id() + "/vorschau?v=" + document.version(),
                    Ui.icon("external"), new Span("PDF öffnen"));
            open.setTarget("_blank");
            open.setClassName("button");
            actions.add(open);
        }
        if (document.isDone()) {
            actions.add(Ui.secondary("Erneut verarbeiten", this::reprocess));
        }
        if (admin) {
            actions.add(Ui.danger("Löschen", () -> Ui.confirm(
                    "Dieses Dokument endgültig löschen? Auch das Original wird entfernt.", "Löschen", this::delete)));
        }
        Details details = new Details("Datei", facts, actions);
        details.addClassName("fold");
        return details;
    }

    private static DescriptionList.Description fact(DescriptionList list, String term, String value) {
        DescriptionList.Description description = new DescriptionList.Description(value);
        list.add(new DescriptionList.Term(term), description);
        return description;
    }

    private void delete() {
        if (!admin) {
            return;
        }
        Document current = documents.find(document.id()).orElse(document);
        if (!service.delete(current)) {
            Ui.error("Das Dokument wird gerade verarbeitet.");
            return;
        }
        activities.record(username, ActivityKind.DELETED, 1);
        Ui.success("„" + current.title() + "“ wurde gelöscht.");
        UI.getCurrent().navigate(DocumentsView.class);
    }
}
