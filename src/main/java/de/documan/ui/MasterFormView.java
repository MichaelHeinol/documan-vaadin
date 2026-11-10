package de.documan.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.dao.DuplicateKeyException;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.Algorithm;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.MasterRepository;
import de.documan.service.CategoryPaths;
import de.documan.service.DocumentService;
import jakarta.annotation.security.PermitAll;

/** Einen Eintrag der Stammdaten anlegen oder bearbeiten. */
@Route(value = "stammdaten/:slug([a-z]+)/:id(neu|[0-9]{1,18})", layout = MainLayout.class)
@PermitAll
public class MasterFormView extends Div implements BeforeEnterObserver, HasDynamicTitle {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_MATCH_LENGTH = 500;
    private static final Long NONE = -1L;

    private final MasterRepository master;
    private final DocumentService documents;
    private MasterKind kind;
    private MasterItem item;

    private TextField name;
    private Input color;
    private Span colorError;
    private Select<Long> category;
    private Select<Algorithm> algorithm;
    private TextField match;
    private Checkbox ignoreCase;

    public MasterFormView(MasterRepository master, DocumentService documents) {
        this.master = master;
        this.documents = documents;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Optional<MasterKind> found = MasterKind.bySlug(event.getRouteParameters().get("slug").orElse(""));
        String id = event.getRouteParameters().get("id").orElse("neu");
        if (found.isEmpty()) {
            event.forwardTo(DashboardView.class);
            return;
        }
        kind = found.get();
        item = id.equals("neu") ? null : master.find(kind, Long.parseLong(id)).orElse(null);
        if (!id.equals("neu") && item == null) {
            Ui.warning("Diesen Eintrag gibt es nicht mehr.");
            event.forwardTo("stammdaten/" + kind.slug());
            return;
        }
        render();
    }

    @Override
    public String getPageTitle() {
        return (kind == null ? "Stammdaten" : item != null ? item.name() : kind.singular()) + " · DocuMan";
    }

    private CategoryPaths paths() {
        return new CategoryPaths(master.categories());
    }

    private void render() {
        removeAll();
        Anchor back = Ui.link("stammdaten/" + kind.slug(), Ui.icon("arrow-left"), new Span(kind.plural()));
        back.setClassName("back-link");
        Header head = new Header(new H1(item != null ? item.name() : kind.singular() + " anlegen"));
        head.setClassName("page-head");

        Div form = Ui.div("panel form-stack narrow");
        name = new TextField("Name");
        name.setMaxLength(MAX_NAME_LENGTH);
        name.setRequired(true);
        name.setValue(item == null ? "" : item.name());
        form.add(DocumentView.field(name));

        if (kind.hasColor()) {
            // Der Farbwähler des Browsers, wie in der Java-Fassung.
            color = new Input();
            color.setType("color");
            color.setId("color");
            color.setValue(item == null ? MasterItem.DEFAULT_COLOR : item.color());
            colorError = Ui.span("field-error", "");
            colorError.setVisible(false);
            NativeLabel label = new NativeLabel("Farbe");
            label.setFor("color");
            form.add(Ui.div("field", label, color, colorError));
        }

        if (kind == MasterKind.DOCUMENT_TYPE || kind == MasterKind.CATEGORY) {
            form.add(categoryField());
        }
        if (kind == MasterKind.CATEGORY && item != null) {
            List<String> types = master.categoryTypes().getOrDefault(item.id(), List.of());
            Div field = Ui.div("field", Ui.span("field-label", "Dokumenttypen"));
            field.add(types.isEmpty()
                    ? Ui.paragraph("help", "Noch keine. Die Kategorie eines Typs wählen Sie beim jeweiligen Dokumenttyp.")
                    : new Paragraph(String.join(", ", types)));
            form.add(field);
        }

        if (kind.hasRules()) {
            algorithm = new Select<>();
            algorithm.setLabel("Zuordnung");
            algorithm.setItems(Algorithm.values());
            algorithm.setItemLabelGenerator(Algorithm::label);
            algorithm.setValue(item == null ? Algorithm.NONE : item.algorithm());
            match = new TextField("Suchbegriffe");
            match.setMaxLength(MAX_MATCH_LENGTH);
            match.setValue(item == null ? "" : item.match());
            match.setHelperText("Mehrere Wörter durch Leerzeichen trennen, Wortgruppen in \"Anführungszeichen\".");
            ignoreCase = new Checkbox("Groß-/Kleinschreibung ignorieren", item == null || item.ignoreCase());
            form.add(DocumentView.field(algorithm), DocumentView.field(match), ignoreCase);
        }

        Anchor cancel = Ui.link("stammdaten/" + kind.slug(), "Abbrechen");
        cancel.setClassName("button");
        form.add(Ui.div("form-actions", Ui.primary("Speichern", this::save), cancel));
        add(back, head, form);
    }

    private Div categoryField() {
        CategoryPaths paths = paths();
        List<Map.Entry<Long, String>> choices = paths.ordered();
        if (kind == MasterKind.CATEGORY) {
            // Als übergeordnete Kategorie taugt weder sie selbst noch eine darunter, und es bleibt bei zehn Stufen.
            int height = item == null ? 1 : paths.height(item.id());
            choices = choices.stream()
                    .filter(entry -> item == null || !paths.withDescendants(item.id()).contains(entry.getKey()))
                    .filter(entry -> paths.depth(entry.getKey()) + height <= CategoryPaths.MAX_LEVELS)
                    .toList();
        }
        List<Long> ids = new ArrayList<>();
        ids.add(NONE);
        choices.forEach(entry -> ids.add(entry.getKey()));
        Map<Long, String> labels = new java.util.HashMap<>();
        choices.forEach(entry -> labels.put(entry.getKey(), entry.getValue()));
        category = new Select<>();
        category.setLabel(kind == MasterKind.CATEGORY ? "Übergeordnete Kategorie" : "Kategorie");
        category.setItems(ids);
        category.setItemLabelGenerator(id -> id.equals(NONE)
                ? (kind == MasterKind.CATEGORY ? "Keine, oberste Stufe" : "Keine") : labels.getOrDefault(id, ""));
        Long current = item == null ? null : kind == MasterKind.CATEGORY ? paths.parent(item.id())
                : master.categoryOf(item.id()).orElse(null);
        category.setValue(current != null && ids.contains(current) ? current : NONE);
        Div field = DocumentView.field(category);
        if (kind == MasterKind.DOCUMENT_TYPE) {
            Paragraph help = new Paragraph(new Span("Gilt für Dokumente dieses Typs ohne eigene Kategorie. Die Kategorien "
                    + "bilden die obersten Ordnerebenen der Ablage. "), Ui.link("stammdaten/kategorien", "Kategorien verwalten"));
            help.setClassName("help");
            field.add(help);
        } else {
            field.add(Ui.paragraph("help", "Bis zu zehn Stufen, etwa Finanzen / Bank / Girokonto."));
        }
        return field;
    }

    private Long chosenCategory() {
        Long value = category == null ? null : category.getValue();
        return value == null || value.equals(NONE) ? null : value;
    }

    private boolean validate() {
        boolean valid = true;
        String text = name.getValue().strip();
        name.setInvalid(false);
        if (text.isEmpty()) {
            name.setErrorMessage("Bitte einen Namen angeben.");
            name.setInvalid(true);
            valid = false;
        } else if (text.length() > MAX_NAME_LENGTH) {
            name.setErrorMessage("Der Name darf höchstens " + MAX_NAME_LENGTH + " Zeichen lang sein.");
            name.setInvalid(true);
            valid = false;
        }
        if (category != null) {
            category.setInvalid(false);
            Long chosen = chosenCategory();
            if (chosen != null && master.find(MasterKind.CATEGORY, chosen).isEmpty()) {
                category.setErrorMessage("Diese Kategorie gibt es nicht mehr.");
                category.setInvalid(true);
                valid = false;
            } else if (kind == MasterKind.CATEGORY && chosen != null) {
                CategoryPaths paths = paths();
                if (item != null && paths.withDescendants(item.id()).contains(chosen)) {
                    category.setErrorMessage("Eine Kategorie kann nicht unter sich selbst stehen.");
                    category.setInvalid(true);
                    valid = false;
                } else if (paths.depth(chosen) + (item == null ? 1 : paths.height(item.id())) > CategoryPaths.MAX_LEVELS) {
                    category.setErrorMessage("Mehr als " + CategoryPaths.MAX_LEVELS + " Stufen sind nicht möglich.");
                    category.setInvalid(true);
                    valid = false;
                }
            }
        }
        if (match != null) {
            match.setInvalid(false);
            if (match.getValue().length() > MAX_MATCH_LENGTH) {
                match.setErrorMessage("Die Suchbegriffe dürfen höchstens " + MAX_MATCH_LENGTH + " Zeichen lang sein.");
                match.setInvalid(true);
                valid = false;
            } else if (algorithm.getValue() == Algorithm.REGEX) {
                try {
                    Pattern.compile(match.getValue());
                } catch (PatternSyntaxException invalid) {
                    match.setErrorMessage("Ungültiger regulärer Ausdruck: " + invalid.getDescription());
                    match.setInvalid(true);
                    valid = false;
                }
            }
        }
        if (color != null) {
            boolean ok = color.getValue() != null && COLOR.matcher(color.getValue()).matches();
            colorError.setText(ok ? "" : "Bitte eine Farbe im Format #rrggbb wählen.");
            colorError.setVisible(!ok);
            valid &= ok;
        }
        return valid;
    }

    private void save() {
        if (!validate()) {
            return;
        }
        String text = name.getValue().strip();
        boolean rules = kind.hasRules();
        String terms = rules ? match.getValue() : "";
        Algorithm chosenAlgorithm = rules ? algorithm.getValue() : Algorithm.NONE;
        boolean ignore = !rules || ignoreCase.getValue();
        String chosenColor = color == null ? MasterItem.DEFAULT_COLOR : color.getValue();
        Long categoryId = chosenCategory();
        try {
            if (kind == MasterKind.CATEGORY) {
                master.saveCategory(item == null ? null : item.id(), text, categoryId);
            } else if (item == null) {
                long id = master.insert(kind, text, chosenAlgorithm, terms, ignore, chosenColor);
                if (kind == MasterKind.DOCUMENT_TYPE) {
                    master.setCategory(id, categoryId);
                }
            } else {
                master.update(kind, item.id(), text, chosenAlgorithm, terms, ignore, chosenColor);
                if (kind == MasterKind.DOCUMENT_TYPE) {
                    master.setCategory(item.id(), categoryId);
                }
            }
            if (item != null) {
                // Zieht Suchvektor und Ablage nach, auch wenn sich nur die Kategorie geändert hat.
                documents.reindexMaster(kind, item.id());
            }
        } catch (DuplicateKeyException taken) {
            name.setErrorMessage(kind == MasterKind.CATEGORY ? "Diesen Namen gibt es auf dieser Stufe schon."
                    : "Dieser Name ist bereits vergeben.");
            name.setInvalid(true);
            return;
        }
        Ui.success("„" + text + "“ gespeichert.");
        MasterListView.open(kind);
    }
}
