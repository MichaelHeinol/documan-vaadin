package de.documan.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.config.DocuManProperties;
import de.documan.service.Notifications;

@Route(value = "login", layout = AuthLayout.class)
@PageTitle("Anmelden · DocuMan")
@AnonymousAllowed
public class LoginView extends Main implements BeforeEnterObserver {

    // Hinweise nach Abmeldung, Registrierung oder neuem Passwort, je nach Parameter der Adresse.
    private static final Map<String, String> NOTES = notes();

    private final LoginForm form = new LoginForm();
    private final Div messages = new Div();

    private static Map<String, String> notes() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("abgemeldet", "Sie wurden abgemeldet.");
        notes.put("inaktiv", "Sie wurden nach einer Stunde Inaktivität abgemeldet.");
        notes.put("registriert", "Fast geschafft: Bitte öffnen Sie den Link in der E-Mail, die wir Ihnen geschickt haben.");
        notes.put("bestaetigt", "Ihr Konto ist aktiv. Sie können sich jetzt anmelden.");
        notes.put("passwortAngefordert", "Gehört zu dieser Adresse ein Konto, ist ein Link zum neuen Passwort unterwegs.");
        notes.put("passwortGesetzt", "Ihr Passwort ist gespeichert. Sie können sich jetzt anmelden.");
        notes.put("emailBestaetigt", "Ihre E-Mail-Adresse wurde geändert.");
        return notes;
    }

    public LoginView(DocuManProperties properties, Notifications notifications) {
        addClassName("auth-sheet");
        Image logo = new Image("favicon.svg", "");
        logo.setWidth("44px");
        logo.setHeight("44px");

        form.setAction("login");
        form.setI18n(german());
        form.setForgotPasswordButtonVisible(false);

        add(logo, new H1("DocuMan"), Ui.paragraph("auth-note", "Melden Sie sich an, um auf Ihr Archiv zuzugreifen."),
                messages, form);
        if (notifications.available()) {
            Div link = Ui.div("auth-link", Ui.link("passwort/vergessen", "Passwort vergessen?"));
            add(link);
        }
        if (properties.registrationOpen()) {
            add(Ui.div("auth-link", Ui.link("registrieren", "Konto anlegen")));
        }
    }

    private static LoginI18n german() {
        LoginI18n i18n = LoginI18n.createDefault();
        LoginI18n.Form texts = i18n.getForm();
        texts.setTitle("");
        texts.setUsername("Benutzername");
        texts.setPassword("Passwort");
        texts.setSubmit("Anmelden");
        LoginI18n.ErrorMessage error = i18n.getErrorMessage();
        error.setTitle("Anmeldung fehlgeschlagen");
        error.setMessage("Benutzername oder Passwort stimmen nicht.");
        error.setUsername("Bitte den Benutzernamen eingeben.");
        error.setPassword("Bitte das Passwort eingeben.");
        return i18n;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!Ui.username().isEmpty() && !"anonymousUser".equals(Ui.username())) {
            event.forwardTo("");
            return;
        }
        var parameters = event.getLocation().getQueryParameters().getParameters();
        form.setError(parameters.containsKey("error"));
        messages.removeAll();
        NOTES.forEach((key, text) -> {
            if (parameters.containsKey(key)) {
                messages.add(Ui.paragraph("auth-info", text));
            }
        });
    }
}
