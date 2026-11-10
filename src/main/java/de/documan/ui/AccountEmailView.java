package de.documan.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import jakarta.annotation.security.PermitAll;

/** Nur für Konten ohne Adresse. Eine vorhandene ändert man unter „Mein Konto“ mit Bestätigung. */
@Route(value = "konto/email", layout = MainLayout.class)
@PageTitle("E-Mail-Adresse · DocuMan")
@PermitAll
public class AccountEmailView extends Div implements BeforeEnterObserver {

    private final UserRepository users;

    public AccountEmailView(UserRepository users) {
        this.users = users;
        Header head = new Header(new Div(new H1("E-Mail-Adresse eintragen"),
                new Paragraph("Über diese Adresse erhalten Sie Nachrichten von DocuMan. Ohne sie geht es nicht weiter.")));
        head.setClassName("page-head");
        EmailField email = new EmailField("E-Mail-Adresse");
        email.setMaxLength(254);
        email.setRequired(true);
        Div form = Ui.div("panel form-stack narrow", DocumentView.field(email));
        form.add(Ui.div("form-actions", Ui.primary("Speichern", () -> {
            if (!Forms.show(email, Accounts.emailProblem(email.getValue()))) {
                return;
            }
            AppUser user = users.findByUsername(Ui.username()).orElseThrow();
            if (user.email().isEmpty()) {
                users.setEmail(user.id(), email.getValue().strip());
                Ui.success("Die E-Mail-Adresse wurde gespeichert.");
            }
            VaadinSetup.markEmailChecked();
            UI.getCurrent().navigate(DashboardView.class);
        })));
        add(head, form);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (users.findByUsername(Ui.username()).filter(user -> !user.email().isEmpty()).isPresent()) {
            event.forwardTo(AccountView.class);
        }
    }
}
