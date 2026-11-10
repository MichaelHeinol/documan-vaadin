package de.documan.ui;

import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.config.DocuManProperties;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;
import de.documan.service.Notifications;

/** Konto anlegen. Aktiv wird es erst, wenn das Passwort über den Link in der Mail festgelegt ist. */
@Route(value = "registrieren", layout = AuthLayout.class)
@PageTitle("Konto anlegen · DocuMan")
@AnonymousAllowed
public class RegisterView extends AuthPage implements BeforeEnterObserver {

    private final DocuManProperties properties;

    public RegisterView(DocuManProperties properties, UserRepository users, AccountTokens tokens,
            Notifications notifications, Accounts accounts) {
        super("Konto anlegen");
        this.properties = properties;
        note("Sie erhalten eine E-Mail mit einem Link. Dort legen Sie Ihr Passwort fest, damit ist das Konto aktiv.");
        TextField username = new TextField("Benutzername");
        username.setMaxLength(150);
        username.setRequired(true);
        username.setAutofocus(true);
        TextField firstName = new TextField("Vorname");
        firstName.setMaxLength(AccountView.MAX_NAME_LENGTH);
        TextField lastName = new TextField("Nachname");
        lastName.setMaxLength(AccountView.MAX_NAME_LENGTH);
        EmailField email = new EmailField("E-Mail-Adresse");
        email.setMaxLength(254);
        email.setRequired(true);

        Button submit = new Button("Konto anlegen", click -> {
            users.deleteExpiredPending(Instant.now().minus(AccountTokens.LIFETIME));
            String name = username.getValue().strip();
            boolean ok = Forms.show(username, !Accounts.USERNAME.matcher(name).matches()
                    ? "Bitte einen Namen aus Buchstaben, Ziffern und . @ + - _ angeben."
                    : users.findByUsername(name).isPresent() ? "Dieser Name ist bereits vergeben." : null);
            ok &= Forms.show(firstName, AccountView.nameProblem(firstName.getValue()));
            ok &= Forms.show(lastName, AccountView.nameProblem(lastName.getValue()));
            ok &= Forms.show(email, Accounts.emailProblem(email.getValue()));
            if (!ok) {
                return;
            }
            String address = email.getValue().strip();
            // Dieselbe Antwort wie sonst: Die Seite verrät nicht, welche Adressen ein Konto haben.
            // Es geht keine Mail hinaus, sonst ließe sich eine fremde Adresse damit überhäufen.
            if (users.emailInUse(address)) {
                Ui.navigate("login?registriert");
                return;
            }
            try {
                // Ohne eigenes Passwort: Es entsteht erst mit dem Link, das bestätigt zugleich die Adresse.
                long id = users.insertPending(name, accounts.unknownPassword(), address, firstName.getValue().strip(),
                        lastName.getValue().strip());
                String token = tokens.issue(AccountTokens.REGISTRATION, id);
                if (notifications.confirmRegistration(name, address, accounts.link("bestaetigen/konto", token)).sent()) {
                    Ui.navigate("login?registriert");
                    return;
                }
                users.delete(id);
                Forms.show(email, "Die Aktivierungsmail ließ sich nicht verschicken. Bitte versuchen Sie es später "
                        + "noch einmal.");
            } catch (DuplicateKeyException taken) {
                Forms.show(username, "Dieser Name ist bereits vergeben.");
            }
        });
        submit.addThemeVariants(ButtonVariant.PRIMARY);
        submit.addClassName("wide");
        body.add(DocumentView.field(username), DocumentView.field(firstName), DocumentView.field(lastName),
                DocumentView.field(email), submit);
        loginLink("Zurück zur Anmeldung");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!properties.registrationOpen()) {
            event.forwardTo(LoginView.class);
        }
    }
}
