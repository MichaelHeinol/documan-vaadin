package de.documan.ui;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.timepicker.TimePicker;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.config.DocuManProperties;
import de.documan.domain.AppUser;
import de.documan.domain.BackupSettings;
import de.documan.domain.ReportChart;
import de.documan.domain.ReportSection;
import de.documan.domain.ReportSettings;
import de.documan.repo.ReportRepository;
import de.documan.repo.UserRepository;
import de.documan.service.DailyReportService;
import de.documan.service.Notifications;
import jakarta.annotation.security.RolesAllowed;

/** Zeitpunkt, Inhalt und Empfänger der täglichen Übersicht. */
@Route(value = "uebersichtsmail", layout = MainLayout.class)
@PageTitle("Übersichtsmail · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class ReportView extends Div {

    private final ReportRepository reports;
    private final DailyReportService dailyReport;
    private final UserRepository users;
    private final DocuManProperties properties;
    private final Div state = new Div();

    public ReportView(ReportRepository reports, DailyReportService dailyReport, UserRepository users,
            DocuManProperties properties) {
        this.reports = reports;
        this.dailyReport = dailyReport;
        this.users = users;
        this.properties = properties;
        Header head = new Header(new Div(new H1("Übersichtsmail"), new Paragraph("Die tägliche Übersicht fasst die "
                + "letzten 24 Stunden zusammen und geht per E-Mail an die gewählten Empfänger.")),
                Ui.secondary("Jetzt an mich senden", this::sendToMe));
        head.setClassName("page-head");
        add(head, state, form());
        showState();
    }

    private List<AppUser> superusers() {
        return users.all().stream().filter(user -> user.superuser() && user.active() && !user.pending())
                .sorted(Comparator.comparing(AppUser::username)).toList();
    }

    private void showState() {
        state.removeAll();
        ReportSettings config = reports.load();
        if (properties.getEmailHost().isEmpty()) {
            state.add(new Html("<div class=\"status-note failed\" role=\"status\">Der E-Mail-Versand ist nicht "
                    + "eingerichtet. Dafür gehören <span class=\"mono\">EMAIL_HOST</span> und die übrigen "
                    + "<span class=\"mono\">EMAIL_</span>-Werte in die <span class=\"mono\">.env</span>.</div>"));
            return;
        }
        String next = dailyReport.nextSendText(config, Instant.now());
        state.add(Ui.div("status-note", new com.vaadin.flow.component.html.Span(next.isEmpty()
                ? "Die Übersicht ist ausgeschaltet."
                : "Zeitplan: " + DailyReportService.scheduleText(config) + ". Nächster Versand: " + next + " an "
                        + dailyReport.recipients(config).size() + " Empfänger.")));
    }

    private static Select<ReportChart> chart(String label, ReportChart value) {
        Select<ReportChart> select = new Select<>();
        select.setLabel(label);
        select.setItems(ReportChart.values());
        select.setItemLabelGenerator(ReportChart::label);
        select.setValue(value);
        return select;
    }

    private Section form() {
        ReportSettings config = reports.load();
        Checkbox enabled = new Checkbox("Übersicht verschicken", config.enabled());
        TimePicker time = new TimePicker("Uhrzeit");
        time.setLocale(Locale.GERMANY);
        time.setValue(config.time());
        CheckboxGroup<Integer> weekdays = new CheckboxGroup<>("Wochentage");
        weekdays.addClassName("tag-group");
        weekdays.setItems(IntStream.range(0, BackupSettings.WEEKDAYS.size()).boxed().toList());
        weekdays.setItemLabelGenerator(BackupSettings.WEEKDAYS::get);
        weekdays.select(config.weekdays());
        CheckboxGroup<ReportSection> sections = new CheckboxGroup<>("Abschnitte");
        sections.addClassName("tag-group");
        sections.setItems(ReportSection.values());
        sections.setItemLabelGenerator(ReportSection::label);
        sections.select(config.sections());
        Select<ReportChart> sources = chart("Herkunft als", config.sourcesChart());
        Select<ReportChart> types = chart("Dokumenttypen als", config.typesChart());
        Select<ReportChart> categories = chart("Kategorien als", config.categoriesChart());
        Select<ReportChart> storage = chart("Speicher als", config.storageChart());
        List<AppUser> superusers = superusers();
        CheckboxGroup<AppUser> receivers = new CheckboxGroup<>("Superuser");
        receivers.addClassName("tag-group");
        receivers.setItems(superusers);
        receivers.setItemLabelGenerator(user -> user.username() + " ("
                + (user.email().isEmpty() ? "keine E-Mail-Adresse" : user.email()) + ")");
        receivers.select(superusers.stream().filter(user -> !config.excluded().contains(user.id())).toList());
        receivers.setHelperText("Abgewählte Superuser bekommen die Übersicht nicht. Ohne E-Mail-Adresse geht nichts an sie.");
        TextArea extra = new TextArea("Weitere Empfänger");
        extra.setMinRows(3);
        extra.setValue(String.join("\n", config.extraRecipients()));
        extra.setHelperText("Eine Adresse je Zeile. Diese Empfänger bekommen die Übersicht zusätzlich.");

        Section section = new Section();
        section.setClassName("panel form-stack page-section");
        section.add(new H2("Zeitpunkt"), enabled, Ui.div("form-row", DocumentView.field(time)), Ui.div("field", weekdays),
                new H2("Inhalt"), Ui.div("field", sections),
                Ui.div("form-row", DocumentView.field(sources), DocumentView.field(types), DocumentView.field(categories),
                        DocumentView.field(storage)),
                Ui.paragraph("help", "Torten kommen als Bilder in der Mail mit, die Zahlen und Anteile stehen in einer "
                        + "Legende daneben. Die neuen Dokumente je Tag bleiben immer Balken."),
                new H2("Empfänger"), Ui.div("field", receivers), DocumentView.field(extra));
        section.add(Ui.div("form-actions", Ui.primary("Speichern", () -> {
            LocalTime chosen = time.getValue();
            boolean ok = Forms.show(time, chosen == null ? "Bitte eine Uhrzeit wie 07:00 angeben." : null);
            ok &= Forms.show(weekdays, weekdays.getSelectedItems().isEmpty() ? "Bitte mindestens einen Wochentag wählen."
                    : null);
            ok &= Forms.show(sections, sections.getSelectedItems().isEmpty() ? "Bitte mindestens einen Abschnitt wählen."
                    : null);
            List<String> addresses = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String invalid = null;
            for (String address : extra.getValue().split("[\\s,;]+")) {
                if (address.isEmpty()) {
                    continue;
                }
                if (Accounts.emailProblem(address) != null) {
                    invalid = invalid == null ? "„" + address + "“ ist keine gültige E-Mail-Adresse." : invalid;
                } else if (seen.add(address.toLowerCase(Locale.ROOT))) {
                    addresses.add(address);
                }
            }
            ok &= Forms.show(extra, invalid);
            if (!ok) {
                return;
            }
            Set<Long> excluded = superusers.stream().filter(user -> !receivers.isSelected(user)).map(AppUser::id)
                    .collect(Collectors.toSet());
            List<Integer> days = weekdays.getSelectedItems().stream().sorted().toList();
            List<ReportSection> chosenSections = Arrays.stream(ReportSection.values())
                    .filter(sections::isSelected).toList();
            reports.save(new ReportSettings(enabled.getValue(), chosen, days, chosenSections, sources.getValue(),
                    types.getValue(), storage.getValue(), categories.getValue(), addresses, excluded,
                    reports.load().sentOn()));
            Ui.success("Einstellungen gespeichert.");
            showState();
        })));
        return section;
    }

    private void sendToMe() {
        AppUser user = users.findByUsername(Ui.username()).orElseThrow();
        Notifications.Result result = dailyReport.send(user, Instant.now());
        if (result.sent()) {
            Ui.success("Die Übersicht ging an " + user.email() + ".");
        } else {
            Ui.warning(result.problem());
        }
    }
}
