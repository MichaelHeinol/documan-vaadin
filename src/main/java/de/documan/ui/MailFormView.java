package de.documan.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;

import de.documan.config.AccountConfig;
import de.documan.domain.AfterImport;
import de.documan.domain.AttachmentFilter;
import de.documan.domain.MailAccount;
import de.documan.domain.MailAuth;
import de.documan.domain.MailRule;
import de.documan.domain.MailRuleField;
import de.documan.domain.MailSecurity;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.MailRepository;
import de.documan.repo.MasterRepository;
import de.documan.service.SecretBox;
import de.documan.service.mail.MailImportService;
import de.documan.service.mail.MailProviders;
import de.documan.service.mail.MailProviders.Provider;
import de.documan.service.mail.MicrosoftLogin;
import de.documan.service.mail.SpamMarks;
import jakarta.annotation.security.RolesAllowed;
import jakarta.mail.AuthenticationFailedException;

/**
 * Ein Postfach einrichten oder bearbeiten. Beim Verbinden führt der Assistent in zwei Schritten durch
 * Anmeldung und Abruf, beim Bearbeiten steht alles auf einer Seite. Das Passwort wird nie angezeigt.
 */
@Route(value = "postfaecher/neu/einrichten", layout = MainLayout.class)
@RouteAlias(value = "postfaecher/:id([0-9]{1,18})", layout = MainLayout.class)
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class MailFormView extends Div implements BeforeEnterObserver, HasDynamicTitle {

    private static final int MAX_RULE_LENGTH = 255;
    private static final Long NONE = -1L;

    private record RuleRow(Div row, Select<MailRuleField> field, TextField value) {
    }

    private final MailRepository accounts;
    private final MasterRepository master;
    private final MailImportService imports;
    private final SecretBox secrets;
    private final MailProviders providers;
    private final MicrosoftLogin microsoft;

    private MailAccount existing;
    private MailAuth auth;
    private boolean wizard;
    private String microsoftToken;
    private volatile boolean microsoftWaiting;

    private TextField name;
    private TextField host;
    private IntegerField port;
    private Select<MailSecurity> security;
    private TextField username;
    private PasswordField password;
    private Checkbox enabled;
    private Paragraph testResult;
    private ComboBox<String> folder;
    private IntegerField pollMinutes;
    private IntegerField maximumAgeDays;
    private TextField filterFrom;
    private TextField filterSubject;
    private Select<AttachmentFilter> attachments;
    private Checkbox onlyUnseen;
    private Checkbox skipSpam;
    private Select<AfterImport> afterImport;
    private ComboBox<String> targetFolder;
    private Select<Long> correspondent;
    private Select<Long> documentType;
    private MultiSelectComboBox<MasterItem> tags;
    private final List<RuleRow> rules = new ArrayList<>();
    private final List<String> folders = new ArrayList<>();
    private Div ruleList;
    private Span ruleCount;
    private Paragraph microsoftState;

    public MailFormView(MailRepository accounts, MasterRepository master, MailImportService imports,
            SecretBox secrets, MailProviders providers, MicrosoftLogin microsoft) {
        this.accounts = accounts;
        this.master = master;
        this.imports = imports;
        this.secrets = secrets;
        this.providers = providers;
        this.microsoft = microsoft;
    }

    /** Öffnet Schritt 2 des Assistenten mit den Daten des gewählten oder erkannten Anbieters. */
    static void prepare(String providerKey, String address) {
        UI.getCurrent().navigate("postfaecher/neu/einrichten",
                QueryParameters.simple(Map.of("vorlage", providerKey, "adresse", address)));
    }

    @Override
    public String getPageTitle() {
        return (existing != null ? existing.name() : "Postfach verbinden") + " · DocuMan";
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var id = event.getRouteParameters().get("id");
        microsoftToken = null;
        if (id.isPresent()) {
            existing = accounts.find(Long.parseLong(id.get())).orElse(null);
            if (existing == null) {
                event.forwardTo(MailListView.class);
                return;
            }
            wizard = false;
            auth = existing.auth();
            render(existing, providers.byHost(existing.host()));
        } else {
            existing = null;
            wizard = true;
            var params = event.getLocation().getQueryParameters();
            String key = params.getSingleParameter("vorlage").orElse("");
            String address = params.getSingleParameter("adresse").orElse("").strip();
            Provider provider = providers.byKey(key).or(() -> providers.detect(address)).orElse(providers.other());
            auth = provider.auth();
            MailAccount blank = MailAccount.blank();
            MailAccount prefilled = new MailAccount(0, address, true, provider.host(), provider.port(),
                    provider.security(), address, "", blank.folder(), true, blank.maximumAgeDays(), "", "",
                    blank.attachments(), blank.afterImport(), "", null, null, List.of(), null, "", 0, provider.auth(),
                    true, List.of(), blank.pollMinutes());
            render(prefilled, provider);
        }
    }

    private void render(MailAccount account, Provider provider) {
        removeAll();
        rules.clear();
        Anchor back = Ui.link("postfaecher", Ui.icon("arrow-left"), new Span("Postfächer"));
        back.setClassName("back-link");
        Header head = new Header(new H1(existing != null ? existing.name() : "Postfach verbinden"));
        head.setClassName("page-head");
        add(back, head);

        Section login = loginSection(account, provider);
        Section messages = messagesSection(account);
        Section exclusions = rulesSection(account.exclusionRules());
        Section after = afterSection(account);
        Anchor cancel = Ui.link("postfaecher", "Abbrechen");
        cancel.setClassName("button");
        Button save = Ui.primary("Speichern", this::save);
        Div actions = Ui.div("form-actions");

        if (wizard) {
            var stepper = MailWizardView.stepper(2);
            Button backStep = Ui.secondary("Zurück", () -> { });
            Button nextStep = Ui.primary("Weiter", () -> { });
            List<Section> third = List.of(messages, exclusions, after);
            Runnable showLogin = () -> {
                login.setVisible(true);
                third.forEach(section -> section.setVisible(false));
                backStep.setVisible(false);
                nextStep.setVisible(true);
                save.setVisible(false);
                replaceStepper(stepper, 2);
            };
            Runnable showFetch = () -> {
                login.setVisible(false);
                third.forEach(section -> section.setVisible(true));
                backStep.setVisible(true);
                nextStep.setVisible(false);
                save.setVisible(true);
                replaceStepper(stepper, 3);
            };
            backStep.addClickListener(click -> showLogin.run());
            nextStep.addClickListener(click -> {
                if (validateLogin()) {
                    showFetch.run();
                }
            });
            actions.add(backStep, nextStep, save, cancel);
            add(stepper);
            showLogin.run();
        } else {
            actions.add(save, cancel);
        }
        add(Ui.div("form-pages", login, messages, exclusions, after, actions));
    }

    private static void replaceStepper(com.vaadin.flow.component.html.OrderedList stepper, int step) {
        stepper.removeAll();
        MailWizardView.stepper(step).getChildren().toList().forEach(stepper::add);
    }

    private Section section(String title) {
        Section section = new Section();
        section.setClassName("panel form-stack");
        if (title != null) {
            section.add(new H2(title));
        }
        return section;
    }

    private Section loginSection(MailAccount account, Provider provider) {
        Section section = section("Zugang");
        if (existing == null) {
            Div hint = Ui.div("status-note", new Span(provider.hint() + " "));
            if (provider.linkUrl() != null) {
                Anchor link = new Anchor(provider.linkUrl(), provider.linkLabel());
                link.setTarget("_blank");
                hint.add(link);
            }
            section.add(hint);
        }
        name = new TextField("Bezeichnung");
        name.setMaxLength(100);
        name.setRequired(true);
        name.setValue(account.name());
        host = new TextField("IMAP-Server");
        host.setMaxLength(255);
        host.setRequired(true);
        host.setValue(account.host());
        port = new IntegerField("Port");
        port.setMin(1);
        port.setMax(65535);
        port.setValue(account.port());
        security = new Select<>();
        security.setLabel("Verschlüsselung");
        security.setItems(MailSecurity.values());
        security.setItemLabelGenerator(MailSecurity::label);
        security.setValue(account.security());
        username = new TextField("Benutzername");
        username.setMaxLength(255);
        username.setRequired(true);
        username.setValue(account.username());
        Div hostRow = Ui.div("form-row", Ui.div("field grow", host), DocumentView.field(port),
                DocumentView.field(security));
        Div userRow = Ui.div("form-row", DocumentView.field(username));
        section.add(DocumentView.field(name), hostRow, userRow);

        boolean hasPassword = existing != null && !existing.passwordEncrypted().isEmpty();
        password = new PasswordField("Passwort");
        password.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.NEW_PASSWORD);
        if (hasPassword) {
            password.setHelperText("Leer lassen, um es nicht zu ändern.");
        }
        if (auth == MailAuth.MICROSOFT) {
            section.add(microsoftBlock(hasPassword));
        } else {
            userRow.add(DocumentView.field(password));
        }
        enabled = new Checkbox("Aktiv", account.enabled());
        testResult = Ui.paragraph("state", "");
        testResult.getElement().setAttribute("role", "status");
        testResult.setVisible(false);
        section.add(enabled, Ui.div("actions", Ui.secondary("Verbindung testen", this::testConnection)), testResult);
        return section;
    }

    private Div microsoftBlock(boolean connected) {
        Div block = Ui.div("microsoft-login");
        if (!microsoft.configured()) {
            block.add(Ui.paragraph("status-note failed", "Für die Microsoft-Anmeldung fehlt noch die Client-ID. Die "
                    + "Einrichtung steht in der Anleitung unter E-Mail."));
            return block;
        }
        microsoftState = Ui.paragraph("state", connected ? "Mit Microsoft verbunden." : "");
        microsoftState.getElement().setAttribute("role", "status");
        Div code = Ui.div("");
        code.setVisible(false);
        Button start = Ui.primary(connected ? "Neu verbinden" : "Bei Microsoft anmelden", () -> startMicrosoft(code));
        block.add(start, code, microsoftState);
        return block;
    }

    /** Anmeldung mit Gerätecode: Code auf der Seite von Microsoft eingeben, DocuMan wartet im Hintergrund. */
    private void startMicrosoft(Div codeBox) {
        MicrosoftLogin.DeviceCode code;
        try {
            code = microsoft.start();
        } catch (IOException | InterruptedException error) {
            microsoftState.setText("Die Anmeldung bei Microsoft ist fehlgeschlagen: " + error.getMessage());
            return;
        }
        codeBox.removeAll();
        Anchor open = new Anchor(code.verificationUri(), "Code eingeben");
        open.setTarget("_blank");
        open.setClassName("button");
        codeBox.add(new Paragraph("Öffnen Sie die Seite von Microsoft und geben Sie diesen Code ein:"),
                Ui.span("device-code", code.userCode()), open);
        codeBox.setVisible(true);
        microsoftState.setText("Warte auf die Anmeldung bei Microsoft …");
        UI ui = UI.getCurrent();
        microsoftWaiting = true;
        long until = System.currentTimeMillis() + code.expiresIn() * 1000L;
        Thread.ofVirtual().start(() -> {
            String state = "Die Anmeldung ist abgelaufen. Bitte starten Sie sie neu.";
            String token = null;
            while (microsoftWaiting && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(Math.max(1, code.interval()) * 1000L);
                    MicrosoftLogin.Poll poll = microsoft.poll(code.deviceCode());
                    if (poll.pending()) {
                        continue;
                    }
                    if (poll.error().isEmpty()) {
                        token = secrets.encrypt(poll.refreshToken());
                        state = "Mit Microsoft verbunden.";
                    } else {
                        state = poll.error();
                    }
                    break;
                } catch (IOException error) {
                    state = "Die Anmeldung bei Microsoft ist fehlgeschlagen: " + error.getMessage();
                    break;
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            String finalState = state;
            String finalToken = token;
            ui.access(() -> {
                microsoftWaiting = false;
                if (finalToken != null) {
                    microsoftToken = finalToken;
                }
                codeBox.setVisible(false);
                microsoftState.setText(finalState);
            });
        });
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent event) {
        microsoftWaiting = false;
    }

    private Section messagesSection(MailAccount account) {
        Section section = section("Welche Nachrichten");
        folders.clear();
        folders.add(account.folder());
        folder = freeChoice("Ordner", account.folder());
        folder.setRequired(true);
        pollMinutes = new IntegerField("Abruf alle (Minuten)");
        pollMinutes.setMin(MailAccount.MIN_POLL_MINUTES);
        pollMinutes.setMax(MailAccount.MAX_POLL_MINUTES);
        pollMinutes.setValue(account.pollMinutes());
        pollMinutes.setHelperText("Von 5 Minuten bis 10.080 (eine Woche).");
        maximumAgeDays = new IntegerField("Höchstalter in Tagen");
        maximumAgeDays.setMin(0);
        maximumAgeDays.setValue(account.maximumAgeDays());
        maximumAgeDays.setHelperText("Ältere Nachrichten werden übersprungen. 0 = kein Limit.");
        filterFrom = new TextField("Absender enthält");
        filterFrom.setMaxLength(255);
        filterFrom.setValue(account.filterFrom());
        filterFrom.setHelperText("Leer = alle Absender.");
        filterSubject = new TextField("Betreff enthält");
        filterSubject.setMaxLength(255);
        filterSubject.setValue(account.filterSubject());
        filterSubject.setHelperText("Leer = alle Betreffzeilen.");
        attachments = new Select<>();
        attachments.setLabel("Anhänge");
        attachments.setItems(AttachmentFilter.values());
        attachments.setItemLabelGenerator(AttachmentFilter::label);
        attachments.setValue(account.attachments());
        onlyUnseen = new Checkbox("Nur ungelesene Nachrichten", account.onlyUnseen());
        skipSpam = new Checkbox("Spam überspringen", account.skipSpam());
        section.add(Ui.div("form-row", DocumentView.field(folder), DocumentView.field(pollMinutes),
                DocumentView.field(maximumAgeDays)), Ui.div("form-row", DocumentView.field(filterFrom),
                        DocumentView.field(filterSubject)), DocumentView.field(attachments), onlyUnseen,
                Ui.div("field", skipSpam, Ui.paragraph("help", "Nachrichten, die Ihr Mailanbieter oder Ihr Mailprogramm "
                        + "als Spam kennzeichnet, bleiben unverändert im Postfach.")));
        return section;
    }

    /** Ordnerwahl: Nach dem Verbindungstest stehen die Ordner des Postfachs zur Auswahl. */
    private ComboBox<String> freeChoice(String label, String value) {
        ComboBox<String> box = new ComboBox<>(label);
        box.setAllowCustomValue(true);
        box.setItems(folders);
        box.addCustomValueSetListener(custom -> {
            if (!folders.contains(custom.getDetail())) {
                folders.add(custom.getDetail());
                box.getListDataView().refreshAll();
            }
            box.setValue(custom.getDetail());
        });
        if (value != null && !value.isEmpty()) {
            if (!folders.contains(value)) {
                folders.add(value);
            }
            box.setValue(value);
        }
        return box;
    }

    private Section rulesSection(List<MailRule> existingRules) {
        Section section = section(null);
        ruleCount = Ui.span("card-count", "");
        section.add(Ui.div("rule-head", new H2("Ausschlussregeln"), ruleCount),
                Ui.paragraph("rule-note", "Trifft eine Regel zu, bleibt die Nachricht unverändert im Postfach. Es genügt, "
                        + "wenn eine der Regeln passt. Groß- und Kleinschreibung spielt keine Rolle, beim Absender zählt "
                        + "die Adresse."));
        ruleList = Ui.div("rule-list");
        existingRules.forEach(rule -> addRule(rule.field(), rule.value()));
        if (existingRules.isEmpty()) {
            addRule(MailRuleField.FROM, "");
        }
        Button add = Ui.secondary("Weitere Regel hinzufügen", () -> addRule(MailRuleField.FROM, ""));
        section.add(ruleList, new Div(add));
        updateRuleCount();
        return section;
    }

    private void addRule(MailRuleField chosen, String text) {
        Select<MailRuleField> field = new Select<>();
        field.setAriaLabel("Feld");
        field.setItems(MailRuleField.values());
        field.setItemLabelGenerator(MailRuleField::label);
        field.setValue(chosen);
        TextField value = new TextField();
        value.setAriaLabel("Text");
        value.setMaxLength(MAX_RULE_LENGTH);
        value.setValue(text);
        value.addValueChangeListener(change -> updateRuleCount());
        Div row = Ui.div("rule-row", field, Ui.span("rule-word", "enthält"), value);
        RuleRow rule = new RuleRow(row, field, value);
        Button remove = new Button("×", click -> {
            rules.remove(rule);
            ruleList.remove(row);
            if (rules.isEmpty()) {
                addRule(MailRuleField.FROM, "");
            }
            updateRuleCount();
        });
        remove.setAriaLabel("Regel entfernen");
        remove.addClassName("icon-button");
        row.add(remove);
        rules.add(rule);
        ruleList.add(row);
    }

    private void updateRuleCount() {
        if (ruleCount != null) {
            ruleCount.setText(rules.stream().filter(rule -> !rule.value().getValue().isBlank()).count() + " aktiv");
        }
    }

    private Section afterSection(MailAccount account) {
        Section section = section("Nach dem Import");
        afterImport = new Select<>();
        afterImport.setLabel("Nach dem Import");
        afterImport.setItems(AfterImport.values());
        afterImport.setItemLabelGenerator(AfterImport::label);
        afterImport.setValue(account.afterImport());
        targetFolder = freeChoice("Zielordner", account.targetFolder());
        targetFolder.setHelperText("Nur für „In Ordner verschieben“.");
        correspondent = masterSelect("Absendername zuweisen", MasterKind.CORRESPONDENT, account.assignCorrespondentId());
        documentType = masterSelect("Dokumenttyp zuweisen", MasterKind.DOCUMENT_TYPE, account.assignDocumentTypeId());
        section.add(Ui.div("form-row", DocumentView.field(afterImport), DocumentView.field(targetFolder)),
                Ui.div("form-row", DocumentView.field(correspondent), DocumentView.field(documentType)));
        List<MasterItem> allTags = master.all(MasterKind.TAG);
        tags = new MultiSelectComboBox<>("Tags zuweisen");
        tags.setItems(allTags);
        tags.setItemLabelGenerator(MasterItem::name);
        tags.select(allTags.stream().filter(tag -> account.assignTagIds().contains(tag.id())).toList());
        if (!allTags.isEmpty()) {
            section.add(DocumentView.field(tags));
        }
        return section;
    }

    private Select<Long> masterSelect(String label, MasterKind kind, Long selected) {
        List<MasterItem> items = master.all(kind);
        List<Long> ids = new ArrayList<>();
        ids.add(NONE);
        items.forEach(item -> ids.add(item.id()));
        Select<Long> select = new Select<>();
        select.setLabel(label);
        select.setItems(ids);
        select.setItemLabelGenerator(id -> id.equals(NONE) ? "Keiner"
                : items.stream().filter(item -> item.id() == id).map(MasterItem::name).findFirst().orElse(""));
        select.setValue(selected != null && ids.contains(selected) ? selected : NONE);
        return select;
    }

    private static String text(TextField field) {
        return field.getValue() == null ? "" : field.getValue().strip();
    }

    private boolean validateLogin() {
        boolean ok = Forms.show(name, text(name).isEmpty() ? "Bitte eine Bezeichnung angeben." : null);
        ok &= Forms.show(host, text(host).isEmpty() ? "Bitte den IMAP-Server angeben." : null);
        ok &= Forms.show(port, port.getValue() == null || port.getValue() < 1 || port.getValue() > 65535
                ? "Bitte einen gültigen Port angeben." : null);
        ok &= Forms.show(username, text(username).isEmpty() ? "Bitte den Benutzernamen angeben." : null);
        boolean hasPassword = existing != null && !existing.passwordEncrypted().isEmpty();
        if (auth == MailAuth.MICROSOFT) {
            if (microsoftToken == null && !hasPassword) {
                Ui.error("Bitte melden Sie sich zuerst bei Microsoft an.");
                ok = false;
            }
        } else {
            ok &= Forms.show(password, password.getValue().isEmpty() && !hasPassword ? "Bitte ein Passwort angeben."
                    : null);
        }
        return ok;
    }

    private boolean validate() {
        boolean ok = validateLogin();
        String chosen = folder.getValue() == null ? "" : folder.getValue().strip();
        ok &= Forms.show(folder, chosen.isEmpty() ? "Bitte einen Ordner angeben."
                : skipSpam.getValue() && SpamMarks.isSpamFolder(chosen)
                        ? "Das ist ein Spam-Ordner. Wählen Sie einen anderen oder schalten Sie „Spam überspringen“ aus."
                        : null);
        ok &= Forms.show(maximumAgeDays, maximumAgeDays.getValue() == null || maximumAgeDays.getValue() < 0
                ? "Bitte eine Zahl angeben." : null);
        Integer minutes = pollMinutes.getValue();
        ok &= Forms.show(pollMinutes, minutes != null && (minutes < MailAccount.MIN_POLL_MINUTES
                || minutes > MailAccount.MAX_POLL_MINUTES) ? "Bitte eine Zahl von 5 bis 10.080 angeben." : null);
        String target = targetFolder.getValue() == null ? "" : targetFolder.getValue().strip();
        ok &= Forms.show(targetFolder, afterImport.getValue() == AfterImport.MOVE && target.isEmpty()
                ? "Bitte einen Zielordner angeben." : null);
        if (rules.stream().anyMatch(rule -> rule.value().getValue().strip().length() > MAX_RULE_LENGTH)) {
            Ui.error("Eine Regel darf höchstens " + MAX_RULE_LENGTH + " Zeichen lang sein.");
            ok = false;
        }
        return ok;
    }

    private String credential(MailAccount base) {
        if (auth == MailAuth.MICROSOFT && microsoftToken != null) {
            return microsoftToken;
        }
        boolean newPassword = !password.getValue().isEmpty();
        return auth == MailAuth.PASSWORD && newPassword ? secrets.encrypt(password.getValue())
                : (base == null ? "" : base.passwordEncrypted());
    }

    private MailAccount toAccount(MailAccount base) {
        List<MailRule> chosenRules = rules.stream().filter(rule -> !rule.value().getValue().isBlank())
                .map(rule -> new MailRule(rule.field().getValue(), rule.value().getValue().strip())).toList();
        Long chosenCorrespondent = Objects.equals(correspondent.getValue(), NONE) ? null : correspondent.getValue();
        Long chosenType = Objects.equals(documentType.getValue(), NONE) ? null : documentType.getValue();
        int minutes = pollMinutes.getValue() != null ? pollMinutes.getValue()
                : existing == null ? MailAccount.DEFAULT_POLL_MINUTES : existing.pollMinutes();
        return new MailAccount(existing == null ? 0 : existing.id(), text(name), enabled.getValue(), text(host),
                port.getValue() == null ? 993 : port.getValue(), security.getValue(), text(username), credential(base),
                folder.getValue() == null ? "" : folder.getValue().strip(), onlyUnseen.getValue(),
                maximumAgeDays.getValue() == null ? 0 : maximumAgeDays.getValue(), text(filterFrom),
                text(filterSubject), attachments.getValue(), afterImport.getValue(),
                targetFolder.getValue() == null ? "" : targetFolder.getValue().strip(), chosenCorrespondent, chosenType,
                tags.getSelectedItems().stream().map(MasterItem::id).toList(), null, "", 0, auth, skipSpam.getValue(),
                chosenRules, minutes);
    }

    private void save() {
        if (!validate()) {
            return;
        }
        MailAccount account = toAccount(existing);
        try {
            if (existing == null) {
                accounts.insert(account);
            } else {
                accounts.update(account);
            }
        } catch (DuplicateKeyException taken) {
            Forms.show(name, "Diese Bezeichnung ist bereits vergeben.");
            return;
        }
        Ui.success("Postfach „" + account.name() + "“ gespeichert.");
        UI.getCurrent().navigate(MailListView.class);
    }

    /** Prüft die Eingaben, ohne zu speichern, und bietet danach die Ordner des Postfachs an. */
    private void testConnection() {
        testResult.setVisible(true);
        testResult.removeClassName("bad");
        boolean ok = Forms.show(host, text(host).isEmpty() ? "Bitte den IMAP-Server angeben." : null);
        ok &= Forms.show(port, port.getValue() == null ? "Bitte einen gültigen Port angeben." : null);
        ok &= Forms.show(username, text(username).isEmpty() ? "Bitte den Benutzernamen angeben." : null);
        if (!ok) {
            testResult.setVisible(false);
            return;
        }
        // Sonst ginge das gespeicherte Passwort an einen beliebigen anderen Server.
        MailAccount base = existing != null && text(host).equalsIgnoreCase(existing.host())
                && text(username).equals(existing.username()) ? existing : null;
        MailAccount account = toAccount(base);
        if (account.passwordEncrypted().isEmpty()) {
            testResult.setText(auth == MailAuth.MICROSOFT ? "Bitte melden Sie sich zuerst bei Microsoft an."
                    : "Bitte geben Sie das Passwort ein.");
            testResult.addClassName("bad");
            return;
        }
        try {
            List<String> found = imports.testConnection(account);
            found.forEach(name -> {
                if (!folders.contains(name)) {
                    folders.add(name);
                }
            });
            folder.getListDataView().refreshAll();
            targetFolder.getListDataView().refreshAll();
            testResult.setText("Verbindung erfolgreich. Ordner: " + String.join(", ", found));
        } catch (AuthenticationFailedException refused) {
            String hint = providers.byHost(account.host()).loginHint();
            testResult.setText("Der Server hat die Anmeldung abgelehnt." + (hint.isEmpty() ? "" : " " + hint));
            testResult.addClassName("bad");
        } catch (Exception error) {
            testResult.setText("Verbindung fehlgeschlagen: " + error.getMessage());
            testResult.addClassName("bad");
        }
    }
}
