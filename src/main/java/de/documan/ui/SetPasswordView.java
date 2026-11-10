package de.documan.ui;

import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;

/**
 * Das eigene Passwort über einen Link festlegen: beim ersten Zugang, nach der Registrierung und wenn es
 * vergessen ist. Erst das Absenden wirkt: Schutzprogramme öffnen Links in Mails von selbst.
 */
abstract class SetPasswordView extends AuthPage implements BeforeEnterObserver {

    protected final UserRepository users;
    protected final AccountTokens tokens;
    private final PasswordEncoder encoder;
    private final Accounts accounts;
    private final String note;
    private final PasswordField password = new PasswordField("Neues Passwort");
    private final PasswordField repeat = new PasswordField("Passwort wiederholen");
    private final Button submit;
    private String token = "";

    SetPasswordView(String title, String note, UserRepository users, AccountTokens tokens, PasswordEncoder encoder,
            Accounts accounts) {
        super(title);
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.accounts = accounts;
        this.note = note;
        password.setHelperText("Mindestens 8 Zeichen.");
        password.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.NEW_PASSWORD);
        repeat.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.NEW_PASSWORD);
        submit = new Button("Passwort speichern", click -> save());
        submit.addThemeVariants(ButtonVariant.PRIMARY);
        submit.addClassName("wide");
        body.add(DocumentView.field(password), DocumentView.field(repeat), submit);
        loginLink("Zur Anmeldung");
    }

    /** Das Konto, dessen Passwort der Link festlegen darf. */
    protected abstract Optional<AppUser> owner(String token);

    /** Nach dem Speichern, etwa das Konto aktivieren. Gibt den Hinweis für die Anmeldung zurück. */
    protected abstract String saved(AppUser user);

    /** Für Zugang und neues Passwort: aktiv, nicht wartend, und der Link passt zum jetzigen Passwort. */
    protected Optional<AppUser> passwordLinkOwner(String purpose, String token) {
        return tokens.read(purpose, token).flatMap(claim -> users.find(claim.userId())
                .filter(user -> user.active() && !user.pending())
                .filter(user -> claim.values().equals(List.of(AccountTokens.passwordStamp(user.passwordHash())))));
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        token = event.getLocation().getQueryParameters().getSingleParameter("token").orElse("");
        boolean valid = owner(token).isPresent();
        note(valid ? note : Accounts.INVALID_LINK);
        password.setVisible(valid);
        repeat.setVisible(valid);
        submit.setVisible(valid);
    }

    private void save() {
        Optional<AppUser> user = owner(token);
        if (user.isEmpty()) {
            note(Accounts.INVALID_LINK);
            return;
        }
        String problem = Accounts.passwordProblem(password.getValue());
        boolean ok = Forms.show(password, problem);
        ok &= Forms.show(repeat, problem == null && !password.getValue().equals(repeat.getValue())
                ? "Die beiden Eingaben stimmen nicht überein." : null);
        if (!ok) {
            return;
        }
        users.setPassword(user.get().id(), encoder.encode(password.getValue()));
        accounts.endSessions(user.get().username(), null);
        Ui.navigate("login?" + saved(user.get()));
    }
}
