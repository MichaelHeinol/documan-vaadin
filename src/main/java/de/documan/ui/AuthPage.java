package de.documan.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;

/** Ein Blatt der Seiten ohne Anmeldung mit Logo, Überschrift und Hinweis. */
abstract class AuthPage extends Main {

    private final H1 title = new H1();
    private final Paragraph note = Ui.paragraph("auth-note", "");
    protected final Div body = Ui.div("form-stack");

    AuthPage(String heading) {
        addClassName("auth-sheet");
        Image logo = new Image("favicon.svg", "");
        logo.setWidth("44px");
        logo.setHeight("44px");
        title.setText(heading);
        add(logo, title, note, body);
    }

    void heading(String text) {
        title.setText(text);
    }

    void note(String text) {
        note.setText(text);
    }

    void loginLink(String text) {
        add(Ui.div("auth-link", Ui.link("login", text)));
    }
}
