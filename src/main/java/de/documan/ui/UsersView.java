package de.documan.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.AccountTokens;
import de.documan.service.Notifications;
import de.documan.web.Fmt;
import jakarta.annotation.security.RolesAllowed;

/** Benutzerverwaltung, nur für Superuser. */
@Route(value = "benutzer", layout = MainLayout.class)
@PageTitle("Benutzer · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class UsersView extends Div {

    private final UserRepository users;
    private final AccountTokens tokens;
    private final Notifications notifications;
    private final Accounts accounts;
    private final Fmt fmt;
    private final Grid<AppUser> grid = new Grid<>();

    public UsersView(UserRepository users, AccountTokens tokens, Notifications notifications, Accounts accounts,
            Fmt fmt) {
        this.users = users;
        this.tokens = tokens;
        this.notifications = notifications;
        this.accounts = accounts;
        this.fmt = fmt;
        Anchor create = Ui.link("benutzer/neu", "Benutzer anlegen");
        create.setClassName("button primary");
        Header head = new Header(new Div(new H1("Benutzer"), new Paragraph("Standardbenutzer suchen, bearbeiten und "
                + "ergänzen Dokumente und pflegen ihr eigenes Konto. Löschen, Postfächer, Sicherungen und die "
                + "Benutzerverwaltung bleiben Superusern vorbehalten.")), create);
        head.setClassName("page-head");

        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setAllRowsVisible(true);
        grid.addComponentColumn(user -> {
            Anchor link = Ui.link("benutzer/" + user.id(), user.username());
            link.setClassName("name-link");
            return link;
        }).setHeader("Name").setFlexGrow(2);
        grid.addColumn(user -> user.email().isEmpty() ? "–" : user.email()).setHeader("E-Mail").setFlexGrow(2);
        grid.addColumn(user -> user.superuser() ? "Superuser" : "Standardbenutzer").setHeader("Rolle");
        grid.addComponentColumn(user -> {
            Span state = new Span(user.active() ? "Aktiv" : user.pending() ? "Wartet auf Bestätigung" : "Gesperrt");
            if (!user.active()) {
                state.setClassName("state-bad");
            }
            return state;
        }).setHeader("Status");
        grid.addColumn(user -> user.lastLogin() != null ? fmt.dateTime(user.lastLogin()) : "Noch nie")
                .setHeader("Letzte Anmeldung");
        grid.addComponentColumn(user -> {
            Button access = new Button("Zugangsmail senden", click -> sendAccess(user));
            access.addThemeVariants(ButtonVariant.SMALL);
            Button delete = new Button("Löschen", click -> Ui.confirm("Benutzer „" + user.username()
                    + "“ löschen? Die Dokumente bleiben erhalten.", "Löschen", () -> delete(user)));
            delete.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            return Ui.div("row-actions", access, delete);
        }).setAutoWidth(true).setFlexGrow(0);
        add(head, grid);
        refresh();
    }

    private void refresh() {
        grid.setItems(users.all());
    }

    /** Ein Link, über den der Benutzer sein Passwort selbst festlegt. */
    static Notifications.Result access(AppUser user, AccountTokens tokens, Notifications notifications,
            Accounts accounts) {
        String token = tokens.issuePasswordLink(AccountTokens.ACCESS, user.id(), user.passwordHash());
        return notifications.accessCreated(user, accounts.link("zugang", token));
    }

    static void report(String prefix, Notifications.Result result, String address) {
        if (result.sent()) {
            Ui.success(prefix + "Die Zugangsmail ging an " + address + ".");
        } else {
            Ui.warning(prefix + "Die Zugangsmail wurde nicht verschickt: " + result.problem());
        }
    }

    private void sendAccess(AppUser user) {
        if (user.email().isEmpty()) {
            Ui.warning("Für „" + user.username() + "“ ist keine E-Mail-Adresse eingetragen.");
            return;
        }
        report("", access(user, tokens, notifications, accounts), user.email());
    }

    private void delete(AppUser user) {
        if (user.username().equals(Ui.username())) {
            Ui.error("Das eigene Konto lässt sich nicht löschen.");
        } else if (user.superuser() && user.active() && users.countActiveAdminsExcept(user.id()) == 0) {
            Ui.error("Mindestens ein aktiver Superuser muss bestehen bleiben.");
        } else {
            users.delete(user.id());
            accounts.endSessions(user.username(), null);
            Ui.success("Benutzer „" + user.username() + "“ wurde gelöscht.");
            refresh();
        }
    }
}
