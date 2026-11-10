package de.documan.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Article;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;

import de.documan.config.AccountConfig;
import de.documan.domain.MailAccount;
import de.documan.domain.MailAuth;
import de.documan.repo.MailRepository;
import de.documan.repo.MailRepository.PollProgress;
import de.documan.service.mail.MailImportService;
import de.documan.web.Fmt;
import jakarta.annotation.security.RolesAllowed;

/** Die verbundenen Postfächer mit Stand und Fortschritt des Abrufs. */
@Route(value = "postfaecher", layout = MainLayout.class)
@PageTitle("Postfächer · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class MailListView extends Div {

    // Der Abruf läuft im Worker, die Seite fragt den Stand nur ab.
    private static final int PROGRESS_MILLIS = 2000;

    private record Progress(Div box, ProgressBar bar, Span label) {
    }

    private final MailRepository accounts;
    private final MailImportService imports;
    private final Fmt fmt;
    private final Div cards = Ui.div("mail-cards");
    private final Map<Long, Progress> progress = new HashMap<>();
    private final Set<Long> running = new HashSet<>();
    private Registration polling;

    public MailListView(MailRepository accounts, MailImportService imports, Fmt fmt) {
        this.accounts = accounts;
        this.imports = imports;
        this.fmt = fmt;
        Anchor connect = Ui.link("postfaecher/neu", "Postfach verbinden");
        connect.setClassName("button primary");
        Header head = new Header(new Div(new H1("Postfächer"),
                new Paragraph("Anhänge aus diesen IMAP-Postfächern landen automatisch im Eingang.")), connect);
        head.setClassName("page-head");
        add(head, cards);
        render();
    }

    private void render() {
        cards.removeAll();
        progress.clear();
        List<MailAccount> all = accounts.all();
        if (all.isEmpty()) {
            cards.add(DocumentsView.emptyState("mail", "Noch kein Postfach verbunden",
                    "Zum Beispiel ein eigenes Postfach für Rechnungen, an das Sie Belege weiterleiten.", null));
            return;
        }
        all.forEach(account -> cards.add(card(account)));
        showProgress();
    }

    private Article card(MailAccount account) {
        Article card = new Article();
        card.setClassName("card mail-card");
        Header head = new Header(new H2(Ui.link("postfaecher/" + account.id(), account.name())),
                Ui.span("muted", account.username() + " · " + account.host() + " · " + account.folder()
                        + (account.enabled() ? "" : " · deaktiviert")));
        if (account.auth() == MailAuth.MICROSOFT) {
            head.add(Ui.span("muted", account.auth().label()));
        }
        card.add(head);
        if (!account.lastError().isEmpty()) {
            card.add(Ui.paragraph("state bad", "Letzter Fehler: " + account.lastError()));
        } else if (account.lastCheckAt() != null) {
            card.add(Ui.paragraph("state", "Zuletzt geprüft " + fmt.dateTime(account.lastCheckAt()) + " · "
                    + account.importedCount() + " Dokument(e) importiert"));
        } else {
            card.add(Ui.paragraph("state", "Wird als Nächstes geprüft."));
        }
        int rules = account.exclusionRules().size();
        card.add(Ui.paragraph("mail-plan", "Abruf alle " + account.pollMinutes() + " Minuten"
                + (rules > 0 ? " · " + rules + " Ausschlussregel" + (rules > 1 ? "n" : "") : "")));

        ProgressBar bar = new ProgressBar();
        bar.getElement().setAttribute("aria-label", "Abruf von " + account.name());
        Span label = new Span();
        label.getElement().setAttribute("role", "status");
        Div box = Ui.div("mail-progress", bar, label);
        box.setVisible(false);
        progress.put(account.id(), new Progress(box, bar, label));
        card.add(box);

        Anchor edit = Ui.link("postfaecher/" + account.id(), "Bearbeiten");
        edit.setClassName("button");
        card.add(Ui.div("actions", Ui.secondary("Verbindung testen", () -> test(account)),
                Ui.secondary("Jetzt abrufen", () -> {
                    accounts.markDue(account.id());
                    Ui.info("Das Postfach wird innerhalb der nächsten Minute abgerufen.");
                    showProgress();
                }), edit, Ui.danger("Entfernen", () -> Ui.confirm("Postfach „" + account.name()
                        + "“ entfernen? Importierte Dokumente bleiben erhalten.", "Entfernen", () -> {
                            accounts.delete(account.id());
                            Ui.success("Postfach „" + account.name() + "“ wurde entfernt.");
                            render();
                        }))));
        return card;
    }

    private void test(MailAccount account) {
        try {
            List<String> folders = imports.testConnection(accounts.find(account.id()).orElse(account));
            Ui.success("Verbindung erfolgreich. Ordner: " + String.join(", ", folders));
        } catch (Exception error) {
            Ui.error("Verbindung zu „" + account.name() + "“ fehlgeschlagen: " + error.getMessage());
        }
    }

    /** Zeigt den Fortschritt. Ist ein Abruf fertig, steht sein Ergebnis gleich auf der Karte. */
    private void showProgress() {
        boolean finished = false;
        for (PollProgress state : accounts.pollProgress()) {
            Progress view = progress.get(state.id());
            if (view == null) {
                continue;
            }
            view.box().setVisible(!state.state().equals("idle"));
            if (state.state().equals("running") && state.total() > 0) {
                view.bar().setIndeterminate(false);
                view.bar().setMax(state.total());
                view.bar().setValue(state.done());
                view.label().setText(state.done() + " von " + state.total() + " Nachrichten verarbeitet");
            } else {
                view.bar().setIndeterminate(true);
                view.label().setText(state.state().equals("running") ? "Postfach wird gelesen …"
                        : "Wartet auf den Abruf …");
            }
            if (!state.state().equals("idle")) {
                running.add(state.id());
            } else if (running.remove(state.id())) {
                finished = true;
            }
        }
        if (finished) {
            render();
        }
    }

    @Override
    protected void onAttach(AttachEvent event) {
        polling = Ticker.every(event.getUI(), PROGRESS_MILLIS, this::showProgress);
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (polling != null) {
            polling.remove();
            polling = null;
        }
    }
}
