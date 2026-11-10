package de.documan.ui;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;

@Route(value = "zugang", layout = AuthLayout.class)
@PageTitle("Zugang einrichten · DocuMan")
@AnonymousAllowed
public class AccessView extends SetPasswordView {

    public AccessView(UserRepository users, AccountTokens tokens, PasswordEncoder encoder, Accounts accounts) {
        super("Zugang einrichten", "Legen Sie Ihr Passwort für DocuMan fest. Danach melden Sie sich damit an.", users, tokens, encoder, accounts);
    }

    @Override
    protected Optional<AppUser> owner(String token) {
        return passwordLinkOwner(AccountTokens.ACCESS, token);
    }

    @Override
    protected String saved(AppUser user) {
        return "passwortGesetzt";
    }
}
