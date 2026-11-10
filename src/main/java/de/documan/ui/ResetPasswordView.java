package de.documan.ui;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;

@Route(value = "passwort/neu", layout = AuthLayout.class)
@PageTitle("Neues Passwort · DocuMan")
@AnonymousAllowed
public class ResetPasswordView extends SetPasswordView {

    public ResetPasswordView(UserRepository users, AccountTokens tokens, PasswordEncoder encoder, Accounts accounts) {
        super("Neues Passwort", "Vergeben Sie ein neues Passwort. Danach melden Sie sich damit an.", users, tokens, encoder, accounts);
    }

    @Override
    protected Optional<AppUser> owner(String token) {
        return passwordLinkOwner(AccountTokens.PASSWORD_RESET, token);
    }

    @Override
    protected String saved(AppUser user) {
        return "passwortGesetzt";
    }
}
