package de.documan.ui;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.AppUser;
import de.documan.domain.Look;
import de.documan.domain.LookMode;
import de.documan.domain.Choice;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;
import de.documan.service.DailyReportService;
import de.documan.service.Notifications;
import jakarta.annotation.security.PermitAll;

/** Das eigene Konto: Namen, E-Mail-Adresse, Darstellung, Übersichtsmail an sich selbst. */
@Route(value = "konto", layout = MainLayout.class)
@PageTitle("Mein Konto · DocuMan")
@PermitAll
public class AccountView extends Div {

    static final int MAX_NAME_LENGTH = 150;

    private final UserRepository users;
    private final Notifications notifications;
    private final AccountTokens tokens;
    private final Accounts accounts;
    private final DailyReportService dailyReport;
    private AppUser user;

    public AccountView(UserRepository users, Notifications notifications, AccountTokens tokens, Accounts accounts,
            DailyReportService dailyReport) {
        this.users = users;
        this.notifications = notifications;
        this.tokens = tokens;
        this.accounts = accounts;
        this.dailyReport = dailyReport;
        user = users.findByUsername(Ui.username()).orElseThrow();

        Anchor password = Ui.link("konto/passwort", "Passwort ändern");
        password.setClassName("button");
        Header head = new Header(new Div(new H1("Mein Konto"), new Paragraph("Angemeldet als " + user.username() + ", "
                + (user.superuser() ? "Superuser" : "Standardbenutzer"))), password);
        head.setClassName("page-head");
        add(head, details(), appearance());
        if (user.superuser()) {
            add(report());
        }
    }

    static String nameProblem(String value) {
        return value.strip().length() > MAX_NAME_LENGTH ? "Bitte höchstens " + MAX_NAME_LENGTH + " Zeichen angeben." : null;
    }

    private Div details() {
        TextField firstName = new TextField("Vorname");
        firstName.setMaxLength(MAX_NAME_LENGTH);
        firstName.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.GIVEN_NAME);
        firstName.setValue(user.firstName());
        TextField lastName = new TextField("Nachname");
        lastName.setMaxLength(MAX_NAME_LENGTH);
        lastName.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.FAMILY_NAME);
        lastName.setValue(user.lastName());
        EmailField email = new EmailField("E-Mail-Adresse");
        email.setMaxLength(254);
        email.setRequired(true);
        email.setValue(user.email());
        email.setHelperText("Eine neue Adresse bestätigen Sie über einen Link, den wir an sie schicken.");

        Div form = Ui.div("panel form-stack narrow", DocumentView.field(firstName), DocumentView.field(lastName),
                DocumentView.field(email));
        form.add(Ui.div("form-actions", Ui.primary("Speichern", () -> {
            String first = nameProblem(firstName.getValue());
            String last = nameProblem(lastName.getValue());
            String mail = Accounts.emailProblem(email.getValue());
            Forms.show(firstName, first);
            Forms.show(lastName, last);
            Forms.show(email, mail);
            if (first != null || last != null || mail != null) {
                return;
            }
            users.setNames(user.id(), firstName.getValue().strip(), lastName.getValue().strip());
            String address = email.getValue().strip();
            if (address.equalsIgnoreCase(user.email())) {
                Ui.success("Ihre Angaben wurden gespeichert.");
            } else {
                changeEmail(address);
                email.setValue(user.email());
            }
            user = users.find(user.id()).orElseThrow();
        })));
        return form;
    }

    private void changeEmail(String email) {
        if (!notifications.available()) {
            users.setEmail(user.id(), email);
            Ui.success("Ihre Angaben wurden gespeichert.");
            return;
        }
        String token = tokens.issue(AccountTokens.EMAIL_CHANGE, user.id(), user.email(), email);
        if (notifications.confirmEmail(user.username(), email, accounts.link("bestaetigen/email", token)).sent()) {
            Ui.info("Ihre Angaben wurden gespeichert. Die neue Adresse gilt, sobald Sie den Link in der Mail an " + email
                    + " geöffnet haben.");
        } else {
            Ui.warning("Ihre Angaben wurden gespeichert, die Bestätigungsmail an " + email
                    + " ließ sich aber nicht verschicken. Die E-Mail-Adresse bleibt unverändert.");
        }
    }

    private Div appearance() {
        var current = users.appearance(user.username());
        Select<LookMode> mode = new Select<>();
        mode.setLabel("Helligkeit");
        mode.setItems(LookMode.values());
        mode.setItemLabelGenerator(LookMode::label);
        mode.setValue(Choice.find(LookMode.class, current.mode()).orElse(LookMode.values()[0]));
        Look[] chosen = {Choice.find(Look.class, current.look()).orElse(Look.values()[0])};

        // Kacheln mit Vorschau, wie in der Java-Fassung. Die Auswahl gilt sofort auf der Seite.
        Div looks = Ui.div("look-grid");
        looks.getElement().setAttribute("role", "radiogroup");
        looks.getElement().setAttribute("aria-label", "Aussehen");
        Map<Look, NativeButton> tiles = new EnumMap<>(Look.class);
        for (Look look : Look.values()) {
            Span main = Ui.span("look-main", "");
            main.add(Ui.span("look-bar", ""), Ui.span("look-card", ""));
            Span preview = new Span(Ui.span("look-side", ""), main);
            preview.setClassName("look-preview");
            preview.getElement().setAttribute("data-look", look.value());
            preview.getElement().setAttribute("aria-hidden", "true");
            NativeButton tile = new NativeButton();
            tile.setClassName("look-choice");
            tile.getElement().setAttribute("role", "radio");
            tile.add(preview, new Span(look.label()));
            tile.addClickListener(click -> {
                chosen[0] = look;
                tiles.forEach((key, other) -> mark(other, key == look));
                apply(look, mode.getValue());
            });
            tiles.put(look, tile);
            mark(tile, look == chosen[0]);
            looks.add(tile);
        }
        mode.addValueChangeListener(change -> apply(chosen[0], change.getValue()));

        Div form = Ui.div("panel form-stack narrow look-form", new H2("Darstellung"), Ui.paragraph("help",
                "Die Auswahl gilt für Ihr Konto, auf jedem Gerät. Änderungen sehen Sie sofort, gespeichert werden sie "
                        + "mit dem Knopf."), looks, DocumentView.field(mode));
        form.add(Ui.div("form-actions", Ui.primary("Darstellung speichern", () -> {
            users.setAppearance(user.id(), chosen[0].value(), mode.getValue().value());
            Ui.success("Die Darstellung wurde gespeichert.");
        })));
        return form;
    }

    private static void mark(NativeButton tile, boolean selected) {
        tile.setClassName("selected", selected);
        tile.getElement().setAttribute("aria-checked", String.valueOf(selected));
    }

    /** Setzt Aussehen und Helligkeit auf der offenen Seite. */
    static void apply(Look look, LookMode mode) {
        if (look == null || mode == null) {
            return;
        }
        UI.getCurrent().getPage().executeJs("const root = document.documentElement; root.dataset.look = $0;"
                + " if ($1 === 'auto') { delete root.dataset.theme; } else { root.dataset.theme = $1; }",
                look.value(), mode.value());
    }

    private Div report() {
        Paragraph schedule = new Paragraph(new Span("Zeitplan: " + dailyReport.scheduleText()
                + ". Inhalt, Zeitpunkt und Empfänger stellen Sie unter "), Ui.link("uebersichtsmail", "Übersichtsmail"),
                new Span(" ein."));
        schedule.setClassName("help");
        Div form = Ui.div("panel form-stack narrow", new H2("Tägliche Übersicht"), schedule);
        form.add(Ui.div("form-actions", Ui.secondary("Übersicht jetzt an mich senden", () -> {
            Notifications.Result result = dailyReport.send(user, Instant.now());
            if (result.sent()) {
                Ui.success("Die Übersicht ging an " + user.email() + ".");
            } else {
                Ui.warning(result.problem());
            }
        })));
        return form;
    }
}
