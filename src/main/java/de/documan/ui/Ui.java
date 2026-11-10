package de.documan.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

import de.documan.config.AccountConfig;

/** Kleine Bausteine, die viele Seiten brauchen. */
public final class Ui {

    private static final String SPRITE = readSprite();

    private Ui() {
    }

    private static String readSprite() {
        try (InputStream in = Ui.class.getResourceAsStream("icons.svg")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Die Symbole der Seite. Einmal je Seite eingebunden, die Icons verweisen darauf. */
    public static Html sprite() {
        return new Html(SPRITE);
    }

    public static Html icon(String name) {
        return icon(name, "icon");
    }

    public static Html icon(String name, String classes) {
        return new Html("<svg class=\"" + classes + "\" aria-hidden=\"true\"><use href=\"#i-" + name + "\"/></svg>");
    }

    /** Ein Link innerhalb von DocuMan. Der Router wechselt die Seite, ohne sie neu zu laden. */
    public static Anchor link(String href, Component... content) {
        // Die Seite hat eine base-Adresse, Links gelten deshalb ohne führenden Schrägstrich.
        Anchor anchor = new Anchor(href.startsWith("/") ? href.substring(1) : href);
        anchor.add(content);
        anchor.getElement().setAttribute("router-link", true);
        return anchor;
    }

    public static Anchor link(String href, String text) {
        return link(href, new Text(text));
    }

    /** Ein Link auf eine Datei, etwa einen Download. Der Browser lädt ihn selbst. */
    public static Anchor file(String href, Component... content) {
        Anchor anchor = new Anchor(href);
        anchor.add(content);
        anchor.getElement().setAttribute("router-ignore", true);
        return anchor;
    }

    public static Span span(String classes, String text) {
        Span span = new Span(text);
        span.setClassName(classes);
        return span;
    }

    public static Div div(String classes, Component... content) {
        Div div = new Div(content);
        div.setClassName(classes);
        return div;
    }

    public static Paragraph paragraph(String classes, String text) {
        Paragraph paragraph = new Paragraph(text);
        paragraph.setClassName(classes);
        return paragraph;
    }

    public static Button button(String text, Runnable action) {
        Button button = new Button(text, click -> action.run());
        button.addThemeVariants(ButtonVariant.TERTIARY);
        button.addClassName("plain");
        return button;
    }

    public static Button primary(String text, Runnable action) {
        Button button = new Button(text, click -> action.run());
        button.addThemeVariants(ButtonVariant.PRIMARY);
        return button;
    }

    public static Button secondary(String text, Runnable action) {
        return new Button(text, click -> action.run());
    }

    public static Button danger(String text, Runnable action) {
        Button button = new Button(text, click -> action.run());
        button.addThemeVariants(ButtonVariant.ERROR);
        return button;
    }

    public static void success(String text) {
        show(text, NotificationVariant.SUCCESS, 5000);
    }

    public static void info(String text) {
        show(text, NotificationVariant.INFO, 5000);
    }

    public static void warning(String text) {
        show(text, NotificationVariant.WARNING, 8000);
    }

    /** Fehler bleiben stehen, bis man sie schließt. */
    public static void error(String text) {
        show(text, NotificationVariant.ERROR, 0);
    }

    private static void show(String text, NotificationVariant variant, int duration) {
        Notification notification = new Notification();
        notification.addThemeVariants(variant);
        notification.setDuration(duration);
        notification.setPosition(Notification.Position.TOP_END);
        Span message = new Span(text);
        Button close = new Button(icon("close"), click -> notification.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        close.setAriaLabel("Schließen");
        Div row = div("toast-row", message, close);
        notification.add(row);
        notification.open();
    }

    /** Fragt vor einer Aktion, die sich nicht rückgängig machen lässt. */
    public static void confirm(String question, String confirmLabel, Runnable action) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Sind Sie sicher?");
        dialog.add(new Paragraph(question));
        Button yes = danger(confirmLabel, () -> {
            dialog.close();
            action.run();
        });
        yes.addThemeVariants(ButtonVariant.PRIMARY);
        dialog.getFooter().add(new Button("Abbrechen", click -> dialog.close()), yes);
        dialog.open();
    }

    /** Wechselt zu einer Adresse samt Parametern, etwa „login?registriert“. */
    public static void navigate(String location) {
        int question = location.indexOf('?');
        String path = question < 0 ? location : location.substring(0, question);
        com.vaadin.flow.router.QueryParameters parameters = question < 0 ? com.vaadin.flow.router.QueryParameters.empty()
                : com.vaadin.flow.router.QueryParameters.fromString(location.substring(question + 1));
        com.vaadin.flow.component.UI.getCurrent().navigate(path, parameters);
    }

    public static String username() {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        return user == null ? "" : user.getName();
    }

    public static boolean isAdmin() {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        return user != null && user.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + AccountConfig.ADMIN_ROLE));
    }
}
