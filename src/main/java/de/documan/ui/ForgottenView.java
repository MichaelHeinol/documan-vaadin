package de.documan.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;
import de.documan.service.Notifications;

/** Passwort vergessen: Ein Link zum neuen Passwort geht an die Adresse des Kontos. */
@Route(value = "passwort/vergessen", layout = AuthLayout.class)
@PageTitle("Passwort vergessen · DocuMan")
@AnonymousAllowed
public class ForgottenView extends AuthPage implements BeforeEnterObserver {

    private final Notifications notifications;

    public ForgottenView(UserRepository users, AccountTokens tokens, Notifications notifications, Accounts accounts) {
        super("Passwort vergessen");
        this.notifications = notifications;
        note("Geben Sie die E-Mail-Adresse Ihres Kontos an. Sie erhalten einen Link, über den Sie ein neues Passwort "
                + "vergeben.");
        EmailField email = new EmailField("E-Mail-Adresse");
        email.setMaxLength(254);
        email.setRequired(true);
        email.setAutofocus(true);
        Button submit = new Button("Link anfordern", click -> {
            if (!Forms.show(email, Accounts.emailProblem(email.getValue()))) {
                return;
            }
            // Dieselbe Antwort für jede Adresse: Die Seite verrät nicht, welche ein Konto hat.
            for (AppUser user : users.activeByEmail(email.getValue().strip())) {
                String token = tokens.issuePasswordLink(AccountTokens.PASSWORD_RESET, user.id(), user.passwordHash());
                notifications.passwordReset(user, accounts.link("passwort/neu", token));
            }
            Ui.navigate("login?passwortAngefordert");
        });
        submit.addThemeVariants(ButtonVariant.PRIMARY);
        submit.addClassName("wide");
        body.add(DocumentView.field(email), submit);
        loginLink("Zurück zur Anmeldung");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!notifications.available()) {
            event.forwardTo(LoginView.class);
        }
    }
}
