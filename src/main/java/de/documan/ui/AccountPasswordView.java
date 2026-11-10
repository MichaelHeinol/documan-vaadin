package de.documan.ui;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import jakarta.annotation.security.PermitAll;

/** Das eigene Passwort ändern. Andere Sitzungen desselben Kontos enden dabei. */
@Route(value = "konto/passwort", layout = MainLayout.class)
@PageTitle("Passwort ändern · DocuMan")
@PermitAll
public class AccountPasswordView extends Div {

    public AccountPasswordView(UserRepository users, PasswordEncoder encoder, Accounts accounts) {
        Anchor back = Ui.link("konto", Ui.icon("arrow-left"), new Span("Mein Konto"));
        back.setClassName("back-link");
        Header head = new Header(new H1("Passwort ändern"));
        head.setClassName("page-head");

        PasswordField current = new PasswordField("Aktuelles Passwort");
        current.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.CURRENT_PASSWORD);
        PasswordField password = new PasswordField("Neues Passwort");
        password.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.NEW_PASSWORD);
        password.setHelperText("Mindestens 8 Zeichen.");
        PasswordField repeat = new PasswordField("Neues Passwort wiederholen");
        repeat.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.NEW_PASSWORD);

        Div form = Ui.div("panel form-stack narrow", DocumentView.field(current), DocumentView.field(password),
                DocumentView.field(repeat));
        form.add(Ui.div("form-actions", Ui.primary("Passwort ändern", () -> {
            AppUser user = users.findByUsername(Ui.username()).orElseThrow();
            boolean ok = Forms.show(current, encoder.matches(current.getValue(), user.passwordHash()) ? null
                    : "Das aktuelle Passwort stimmt nicht.");
            String problem = Accounts.passwordProblem(password.getValue());
            ok &= Forms.show(password, problem);
            ok &= Forms.show(repeat, problem == null && !password.getValue().equals(repeat.getValue())
                    ? "Die beiden Eingaben stimmen nicht überein." : null);
            if (!ok) {
                return;
            }
            users.setPassword(user.id(), encoder.encode(password.getValue()));
            accounts.endSessions(user.username(), Accounts.currentSessionId());
            Ui.success("Das Passwort wurde geändert.");
            UI.getCurrent().navigate(DashboardView.class);
        })));
        add(back, head, form);
    }
}
