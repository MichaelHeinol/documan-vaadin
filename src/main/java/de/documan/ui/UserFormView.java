package de.documan.ui;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;
import de.documan.service.Notifications;
import jakarta.annotation.security.RolesAllowed;

/** Einen Benutzer anlegen oder bearbeiten. Neue Benutzer legen ihr Passwort über die Zugangsmail fest. */
@Route(value = "benutzer/:id(neu|[0-9]{1,18})", layout = MainLayout.class)
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class UserFormView extends Div implements BeforeEnterObserver, HasDynamicTitle {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AccountTokens tokens;
    private final Notifications notifications;
    private final Accounts accounts;
    private AppUser existing;

    public UserFormView(UserRepository users, PasswordEncoder encoder, AccountTokens tokens,
            Notifications notifications, Accounts accounts) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.notifications = notifications;
        this.accounts = accounts;
    }

    @Override
    public String getPageTitle() {
        return (existing == null ? "Benutzer anlegen" : existing.username()) + " · DocuMan";
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String id = event.getRouteParameters().get("id").orElse("neu");
        existing = id.equals("neu") ? null : users.find(Long.parseLong(id)).orElse(null);
        if (!id.equals("neu") && existing == null) {
            event.forwardTo(UsersView.class);
            return;
        }
        render();
    }

    private void render() {
        removeAll();
        Anchor back = Ui.link("benutzer", Ui.icon("arrow-left"), new Span("Benutzer"));
        back.setClassName("back-link");
        Header head = new Header(new H1(existing == null ? "Benutzer anlegen" : existing.username()));
        head.setClassName("page-head");

        TextField username = new TextField("Benutzername");
        username.setMaxLength(150);
        username.setRequired(true);
        EmailField email = new EmailField("E-Mail-Adresse");
        email.setMaxLength(254);
        email.setRequired(true);
        if (existing == null) {
            email.setHelperText("An diese Adresse geht die Zugangsmail. Über den Link darin legt der Benutzer sein "
                    + "Passwort selbst fest.");
        }
        Checkbox superuser = new Checkbox("Superuser", existing != null && existing.superuser());
        Checkbox active = new Checkbox("Aktiv", existing == null || existing.active());
        Div form = Ui.div("panel form-stack narrow", DocumentView.field(username), DocumentView.field(email));
        PasswordField password = new PasswordField("Passwort");
        if (existing != null) {
            username.setValue(existing.username());
            email.setValue(existing.email());
            password.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.NEW_PASSWORD);
            password.setHelperText("Leer lassen, um es nicht zu ändern. Lieber über „Zugangsmail senden“ einen Link "
                    + "schicken, dann legt der Benutzer es selbst fest.");
            form.add(DocumentView.field(password));
        }
        form.add(superuser, active);

        Anchor cancel = Ui.link("benutzer", "Abbrechen");
        cancel.setClassName("button");
        form.add(Ui.div("form-actions", Ui.primary("Speichern", () -> {
            String name = username.getValue().strip();
            boolean ok = Forms.show(username, Accounts.USERNAME.matcher(name).matches() ? null
                    : "Bitte einen Namen aus Buchstaben, Ziffern und . @ + - _ angeben.");
            ok &= Forms.show(email, Accounts.emailProblem(email.getValue()));
            boolean passwordGiven = existing != null && !password.getValue().isEmpty();
            if (passwordGiven) {
                ok &= Forms.show(password, Accounts.passwordProblem(password.getValue()));
            }
            boolean losesAdmin = existing != null && existing.superuser() && existing.active()
                    && (!superuser.getValue() || !active.getValue());
            if (losesAdmin && users.countActiveAdminsExcept(existing.id()) == 0) {
                Ui.error("Mindestens ein aktiver Superuser muss bestehen bleiben.");
                ok = false;
            }
            if (!ok) {
                return;
            }
            String address = email.getValue().strip();
            try {
                if (existing == null) {
                    long id = users.insert(name, accounts.unknownPassword(), superuser.getValue(), active.getValue(),
                            address);
                    // Der Benutzer bleibt bestehen, auch wenn der Versand scheitert.
                    UsersView.report("Benutzer „" + name + "“ gespeichert. ",
                            UsersView.access(users.find(id).orElseThrow(), tokens, notifications, accounts), address);
                } else {
                    users.update(existing.id(), name, superuser.getValue(), active.getValue(), address);
                    if (passwordGiven) {
                        users.setPassword(existing.id(), encoder.encode(password.getValue()));
                    }
                    accounts.endSessions(existing.username(), null);
                    Ui.success("Benutzer „" + name + "“ gespeichert.");
                }
                UI.getCurrent().navigate(UsersView.class);
            } catch (DuplicateKeyException taken) {
                Forms.show(username, "Dieser Name ist bereits vergeben.");
            }
        }), cancel));
        add(back, head, form);
    }
}
