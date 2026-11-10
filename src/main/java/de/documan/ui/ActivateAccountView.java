package de.documan.ui;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;

/** Nach der Registrierung: Mit dem Passwort aus diesem Link wird das Konto aktiv. */
@Route(value = "bestaetigen/konto", layout = AuthLayout.class)
@PageTitle("Konto aktivieren · DocuMan")
@AnonymousAllowed
public class ActivateAccountView extends SetPasswordView {

    public ActivateAccountView(UserRepository users, AccountTokens tokens, PasswordEncoder encoder, Accounts accounts) {
        super("Konto aktivieren", "Legen Sie Ihr Passwort fest. Damit ist Ihr Konto aktiv.", users, tokens, encoder,
                accounts);
    }

    @Override
    protected Optional<AppUser> owner(String token) {
        return tokens.read(AccountTokens.REGISTRATION, token).flatMap(claim -> users.find(claim.userId()))
                .filter(AppUser::pending);
    }

    @Override
    protected String saved(AppUser user) {
        users.confirmPending(user.id());
        return "bestaetigt";
    }
}
