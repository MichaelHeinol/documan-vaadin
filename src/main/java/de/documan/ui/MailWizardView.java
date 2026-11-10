package de.documan.ui;

import java.util.HashMap;
import java.util.Map;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.service.mail.MailProviders;
import de.documan.service.mail.MailProviders.Provider;
import jakarta.annotation.security.RolesAllowed;

/** Schritt 1 beim Verbinden eines Postfachs: Adresse eingeben oder Anbieter wählen. */
@Route(value = "postfaecher/neu", layout = MainLayout.class)
@PageTitle("Postfach verbinden · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class MailWizardView extends Div {

    private final Map<String, NativeButton> tiles = new HashMap<>();

    public MailWizardView(MailProviders providers) {
        Anchor back = Ui.link("postfaecher", Ui.icon("arrow-left"), new Span("Postfächer"));
        back.setClassName("back-link");
        Header head = new Header(new Div(new H1("Postfach verbinden"), new Paragraph("Geben Sie die Adresse ein oder "
                + "wählen Sie Ihren Anbieter, DocuMan trägt die Verbindungsdaten ein.")));
        head.setClassName("page-head");

        EmailField address = new EmailField("E-Mail-Adresse");
        address.setPlaceholder("name@example.de");
        address.setAutofocus(true);
        address.setValueChangeMode(ValueChangeMode.LAZY);
        address.setValueChangeTimeout(400);
        Paragraph result = Ui.paragraph("state", "");
        result.getElement().setAttribute("role", "status");
        result.setVisible(false);
        // Erkennt den Anbieter schon beim Tippen und nennt die Verbindungsdaten.
        address.addValueChangeListener(change -> {
            tiles.values().forEach(tile -> tile.removeClassName("detected"));
            var found = providers.detect(change.getValue().strip());
            result.setVisible(found.isPresent());
            found.ifPresent(provider -> {
                result.setText("Erkannt: " + provider.label() + ", " + provider.host() + ":" + provider.port() + " ("
                        + provider.security().label() + ")");
                if (tiles.containsKey(provider.key())) {
                    tiles.get(provider.key()).addClassName("detected");
                }
            });
        });
        Runnable next = () -> MailFormView.prepare("", address.getValue().strip());
        address.addKeyDownListener(Key.ENTER, enter -> next.run());

        Div grid = Ui.div("provider-grid");
        for (Provider provider : providers.all()) {
            NativeButton tile = new NativeButton();
            tile.setClassName("provider");
            tile.add(Ui.span("strong", provider.label()),
                    new Span(provider.host().isEmpty() ? "Server selbst eintragen" : provider.host()));
            tile.addClickListener(click -> MailFormView.prepare(provider.key(), address.getValue().strip()));
            tiles.put(provider.key(), tile);
            grid.add(tile);
        }

        Div panel = Ui.div("panel form-stack", Ui.div("address-row", DocumentView.field(address),
                Ui.primary("Weiter", next)), result, new H2("Oder Anbieter wählen"), grid);
        add(back, head, stepper(1), panel);
    }

    /** Die Schritte des Assistenten, step zählt ab 1. */
    static OrderedList stepper(int step) {
        OrderedList list = new OrderedList();
        list.setClassName("stepper");
        list.getElement().setAttribute("aria-label", "Schritte");
        String[] names = {"Anbieter", "Anmeldung", "Abruf"};
        for (int index = 0; index < names.length; index++) {
            ListItem item = new ListItem(names[index]);
            if (index + 1 < step) {
                item.setClassName("done");
            } else if (index + 1 == step) {
                item.getElement().setAttribute("aria-current", "step");
            }
            list.add(item);
        }
        return list;
    }
}
