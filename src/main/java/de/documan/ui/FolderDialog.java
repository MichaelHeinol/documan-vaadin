package de.documan.ui;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Wählt einen Ordner auf dem NAS, etwa einen Eingangsordner oder das Ziel der Sicherungen. Man klickt sich
 * durch die Unterordner und kann einen neuen anlegen.
 */
class FolderDialog extends Dialog {

    /** Inhalt eines Ordners: sein bereinigter Name und seine Unterordner. */
    record Listing(String folder, List<String> folders) {
    }

    private final Function<String, Listing> list;
    private final BiFunction<String, String, String> create;
    private final String rootLabel;
    private final Paragraph path = Ui.paragraph("folder-path mono", "");
    private final Paragraph problem = Ui.paragraph("field-error", "");
    private final UnorderedList entries = new UnorderedList();
    private String current = "";

    /**
     * @param list liefert den Inhalt eines Ordners, wirft bei Fehlern eine Ausnahme mit lesbarer Meldung
     * @param create legt einen Unterordner an und gibt den neuen Ordner zurück
     */
    FolderDialog(String title, String rootLabel, String start, Function<String, Listing> list,
            BiFunction<String, String, String> create, Consumer<String> chosen) {
        this.list = list;
        this.create = create;
        this.rootLabel = rootLabel;
        setHeaderTitle(title);
        addClassName("folder-dialog");
        setWidth("min(520px, 100vw)");
        entries.setClassName("folder-list");
        problem.setVisible(false);

        TextField name = new TextField();
        name.setPlaceholder("Neuer Ordner");
        name.setMaxLength(100);
        name.setAriaLabel("Name des neuen Ordners");
        add(path, problem, entries, Ui.div("input-with-button", name, Ui.secondary("Anlegen", () -> {
            if (attempt(() -> show(create.apply(current, name.getValue())))) {
                name.clear();
            }
        })));
        getFooter().add(Ui.secondary("Abbrechen", this::close), Ui.primary("Diesen Ordner wählen", () -> {
            close();
            chosen.accept(current);
        }));
        if (!attempt(() -> show(start))) {
            attempt(() -> show(""));
        }
    }

    private boolean attempt(Runnable action) {
        try {
            action.run();
            problem.setVisible(false);
            return true;
        } catch (RuntimeException error) {
            problem.setText(error.getMessage() == null ? "Der Ordner konnte nicht geladen werden." : error.getMessage());
            problem.setVisible(true);
            return false;
        }
    }

    private void show(String folder) {
        Listing listing = list.apply(folder);
        current = listing.folder();
        path.setText(rootLabel + (current.isEmpty() ? "" : " / " + current.replace("/", " / ")));
        entries.removeAll();
        if (!current.isEmpty()) {
            String[] parts = current.split("/");
            entry("↑ Übergeordneter Ordner", String.join("/", Arrays.copyOf(parts, parts.length - 1)));
        }
        listing.folders().forEach(sub -> entry(sub, current.isEmpty() ? sub : current + "/" + sub));
    }

    private void entry(String label, String folder) {
        NativeButton button = new NativeButton(label, click -> attempt(() -> show(folder)));
        entries.add(new ListItem(button));
    }
}
