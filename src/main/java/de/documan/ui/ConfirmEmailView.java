package de.documan.ui;

import java.util.Optional;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;
import de.documan.service.AccountTokens.Claim;

/** Bestätigt eine neue E-Mail-Adresse. Erst der Knopf wirkt, nicht schon das Öffnen des Links. */
@Route(value = "bestaetigen/email", layout = AuthLayout.class)
@PageTitle("E-Mail-Adresse bestätigen · DocuMan")
@AnonymousAllowed
public class ConfirmEmailView extends AuthPage implements BeforeEnterObserver {

    private final UserRepository users;
    private final AccountTokens tokens;
    private final Button confirm;
    private String token = "";

    public ConfirmEmailView(UserRepository users, AccountTokens tokens) {
        super("E-Mail-Adresse bestätigen");
        this.users = users;
        this.tokens = tokens;
        confirm = new Button("Adresse bestätigen", click -> confirm());
        confirm.addThemeVariants(ButtonVariant.PRIMARY);
        confirm.addClassName("wide");
        body.add(confirm);
        loginLink("Zur Anmeldung");
    }

    private Optional<AppUser> owner() {
        return tokens.read(AccountTokens.EMAIL_CHANGE, token).flatMap(claim -> users.find(claim.userId()));
    }

    /** Die neue Adresse aus dem Link. Ändert sich die Adresse, verfallen ältere Links. */
    private Optional<String> newEmail(Optional<AppUser> user) {
        Optional<Claim> claim = tokens.read(AccountTokens.EMAIL_CHANGE, token);
        return claim.filter(found -> user.filter(account -> account.id() == found.userId()
                && account.email().equals(found.values().getFirst())).isPresent())
                .map(found -> found.values().getLast());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        token = event.getLocation().getQueryParameters().getSingleParameter("token").orElse("");
        Optional<String> email = newEmail(owner());
        note(email.map(address -> "Neue Adresse: " + address).orElse(Accounts.INVALID_LINK));
        confirm.setVisible(email.isPresent());
    }

    private void confirm() {
        Optional<AppUser> user = owner();
        Optional<String> email = newEmail(user);
        if (email.isEmpty()) {
            note(Accounts.INVALID_LINK);
            confirm.setVisible(false);
            return;
        }
        users.setEmail(user.orElseThrow().id(), email.get());
        String name = Ui.username();
        if (name.isEmpty() || name.equals("anonymousUser")) {
            Ui.navigate("login?emailBestaetigt");
        } else {
            Ui.success("Ihre E-Mail-Adresse wurde geändert.");
            UI.getCurrent().navigate(AccountView.class);
        }
    }
}
