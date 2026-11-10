package de.documan.ui;

import java.util.Set;

import org.jsoup.nodes.Element;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.server.CustomizedSystemMessages;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;

import de.documan.repo.UserRepository;

/**
 * Verhalten, das für alle Seiten gilt.
 *
 * <ul>
 * <li>Nach einer Stunde ohne Bedienung endet die Sitzung. Die Seite wechselt dann von selbst zur Anmeldung,
 * damit nichts sichtbar bleibt.</li>
 * <li>Aussehen und Hell/Dunkel stehen schon im ersten HTML, die Seite flackert nicht.</li>
 * <li>Konten ohne E-Mail-Adresse tragen sie nach, bevor es weitergeht.</li>
 * </ul>
 */
@Component
@ConditionalOnWebApplication
public class VaadinSetup implements VaadinServiceInitListener {

    private static final String EMAIL_CHECKED = "documan.emailChecked";
    private static final Set<String> WITHOUT_EMAIL_CHECK =
            Set.of("login", "konto/email", "registrieren", "zugang", "passwort/vergessen", "passwort/neu");

    private final UserRepository users;

    public VaadinSetup(UserRepository users) {
        this.users = users;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().setSystemMessagesProvider(info -> {
            CustomizedSystemMessages messages = new CustomizedSystemMessages();
            messages.setSessionExpiredURL("login?inaktiv");
            messages.setSessionExpiredNotificationEnabled(false);
            return messages;
        });
        // Vaadin beendet nur seine eigene Sitzung. Die Anmeldung hängt an der HTTP-Sitzung und endet mit.
        event.getSource().addSessionDestroyListener(destroyed -> invalidate(destroyed.getSession()));
        event.addIndexHtmlRequestListener(response -> appearance(response.getDocument().selectFirst("html")));
        event.getSource().addUIInitListener(init -> init.getUI().addBeforeEnterListener(this::requireEmail));
    }

    private static void invalidate(VaadinSession session) {
        WrappedSession http = session.getSession();
        if (http == null) {
            return;
        }
        try {
            http.invalidate();
        } catch (IllegalStateException alreadyGone) {
            // Die HTTP-Sitzung war schon beendet.
        }
    }

    private static Authentication signedIn() {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        return user == null || !user.isAuthenticated() || user instanceof AnonymousAuthenticationToken ? null : user;
    }

    private void appearance(Element html) {
        html.attr("lang", "de");
        Authentication user = signedIn();
        if (user == null) {
            return;
        }
        var appearance = users.appearance(user.getName());
        html.attr("data-look", appearance.look());
        if (!appearance.mode().equals("auto")) {
            html.attr("data-theme", appearance.mode());
        }
    }

    /** Die Seiten zur Anmeldung, Registrierung und für die Adresse selbst bleiben erreichbar. */
    private void requireEmail(BeforeEnterEvent event) {
        Authentication user = signedIn();
        VaadinSession session = VaadinSession.getCurrent();
        String target = event.getLocation().getPath();
        if (user == null || session == null || session.getAttribute(EMAIL_CHECKED) != null
                || WITHOUT_EMAIL_CHECK.contains(target) || target.startsWith("bestaetigen/")) {
            return;
        }
        boolean missing = users.findByUsername(user.getName()).filter(account -> account.email().isEmpty()).isPresent();
        if (missing) {
            event.rerouteTo("konto/email");
        } else {
            session.setAttribute(EMAIL_CHECKED, Boolean.TRUE);
        }
    }

    /** Nach dem Eintragen der Adresse muss nicht mehr gefragt werden. */
    public static void markEmailChecked() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(EMAIL_CHECKED, Boolean.TRUE);
        }
    }
}
