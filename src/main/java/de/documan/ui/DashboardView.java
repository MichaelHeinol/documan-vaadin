package de.documan.ui;

import static de.documan.ui.Markup.dot;
import static de.documan.ui.Markup.esc;
import static de.documan.ui.Markup.icon;
import static de.documan.ui.Markup.link;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.html.Article;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;

import de.documan.domain.Document;
import de.documan.repo.ActivityRepository;
import de.documan.repo.ActivityRepository.UserActivity;
import de.documan.service.DashboardService;
import de.documan.service.DashboardService.Category;
import de.documan.service.DashboardService.Chart;
import de.documan.service.DashboardService.Dashboard;
import de.documan.service.DashboardService.Pie;
import de.documan.service.DashboardService.Slice;
import de.documan.service.StorageService;
import de.documan.service.StorageService.Usage;
import de.documan.web.Fmt;
import jakarta.annotation.security.PermitAll;

/** Übersicht: Kennzahlen, Eingang, Diagramme, Kategorien und die zuletzt hinzugefügten Dokumente. */
@Route(value = "", layout = MainLayout.class)
@RouteAlias(value = "uebersicht", layout = MainLayout.class)
@PageTitle("Übersicht · DocuMan")
@PermitAll
public class DashboardView extends Div implements Refreshable {

    private final DashboardService dashboard;
    private final ActivityRepository activities;
    private final StorageService storage;
    private final Fmt fmt;
    private final boolean admin;

    public DashboardView(DashboardService dashboard, ActivityRepository activities, StorageService storage, Fmt fmt) {
        this.dashboard = dashboard;
        this.activities = activities;
        this.storage = storage;
        this.fmt = fmt;
        this.admin = Ui.isAdmin();
        refresh();
    }

    @Override
    public void refresh() {
        removeAll();
        Dashboard board = dashboard.build();
        var summary = board.summary();
        Header head = new Header(new H1("Übersicht"));
        head.setClassName("page-head");
        add(head);

        if (summary.failed() > 0) {
            String count = summary.failed() == 1 ? "1 Dokument" : summary.failed() + " Dokumente";
            String verb = summary.failed() == 1 ? "konnte nicht verarbeitet werden." : "konnten nicht verarbeitet werden.";
            add(new Html(link("dokumente?view=failed", "notice",
                    icon("alert") + "<span><strong>" + esc(count) + "</strong> " + verb + "</span>")));
        }
        if (summary.total() == 0) {
            add(welcome());
            return;
        }

        StringBuilder stats = new StringBuilder("<section class=\"stats\" aria-label=\"Kennzahlen\">");
        board.stats().forEach(stat -> stats.append(link(stat.url(), "stat card",
                Markup.tag("span", "stat-label", stat.label()) + Markup.tag("span", "stat-value", stat.value())
                        + Markup.tag("span", "stat-note", stat.note()))));
        add(new Html(stats.append("</section>").toString()));

        add(new Html("<section class=\"dash-split\">" + chartCard(board.chart()) + inboxCard(board.inboxDocuments(),
                summary.inbox()) + "</section>"));

        add(new Html("<section class=\"pie-pair\" aria-label=\"Benutzer\">"
                + pieCard("Eingespielt aus Quelle", documents(board.pie().total())
                        + " nach Benutzer, Postfach und Eingangsordner", board.pie(), "Quelle",
                        "Tortengrafik der Dokumente nach Herkunft.", "")
                + pieCard("Eingang bearbeitet von", documents(board.inboxPie().total())
                        + " abgelegt, je Benutzer aufsummiert", board.inboxPie(), "Bearbeitet von",
                        "Tortengrafik der abgelegten Dokumente nach Benutzer.",
                        "Bisher hat niemand Dokumente aus dem Eingang abgelegt.")
                + "</section>"));

        // Was einzelne Benutzer tun und wie voll das Laufwerk ist, sehen nur Administratoren.
        if (admin) {
            add(new Html(activityCard(activities.overview())));
            add(new Html(storageCard(storage.usage())));
        }

        StringBuilder grid = new StringBuilder("<section class=\"dash-grid\" aria-label=\"Kategorien\">");
        board.categories().forEach(category -> grid.append(categoryCard(category)));
        add(new Html(grid.append("</section>").toString()));

        Section recent = new Section();
        recent.setClassName("dash-section");
        recent.getElement().setAttribute("aria-label", "Zuletzt hinzugefügt");
        Header recentHead = new Header(new H2("Zuletzt hinzugefügt"), Ui.link("dokumente?sort=added", "Alle anzeigen"));
        recentHead.setClassName("section-head");
        Div docs = Ui.div("docs docs-recent");
        for (Document document : board.recentDocuments()) {
            Article card = new Article(DocumentsView.cardLink(document, fmt));
            card.setClassName("doc");
            docs.add(card);
        }
        recent.add(recentHead, docs);
        add(recent);
    }

    private static String documents(long count) {
        return count + (count == 1 ? " Dokument" : " Dokumente");
    }

    private Section welcome() {
        Section welcome = new Section();
        welcome.setClassName("welcome card");
        NativeButton upload = new NativeButton();
        upload.setClassName("welcome-step");
        upload.add(Ui.icon("upload"), Ui.span("strong", "Hochladen"), new Span("Dateien auswählen und hochladen"));
        upload.addClickListener(click -> MainLayout.current().ifPresent(MainLayout::openUpload));
        Div steps = Ui.div("welcome-steps", upload, new Html("<div class=\"welcome-step\">" + icon("folder")
                + "<strong>Eingangsordner</strong><span>Scans in den überwachten Ordner auf dem NAS legen</span></div>"));
        if (admin) {
            steps.add(new Html(link("postfaecher", "welcome-step", icon("mail")
                    + "<strong>E-Mail</strong><span>Postfach verbinden und Anhänge importieren</span>")));
        }
        welcome.add(new H2("Willkommen bei DocuMan"), new Paragraph("Ihr Archiv ist noch leer. So kommen Dokumente hinein:"),
                steps);
        return welcome;
    }

    private static String chartCard(Chart chart) {
        StringBuilder out = new StringBuilder("<article class=\"card\"><header class=\"card-head\">"
                + "<h2>Dokumente nach Monat</h2><span class=\"card-sub\">letzte 12 Monate, nach Dokumentdatum</span>"
                + "</header>");
        if (chart.total() == 0) {
            out.append("<p class=\"card-empty\">In den letzten 12 Monaten gibt es keine Dokumente mit Datum.</p>");
            return out.append("</article>").toString();
        }
        out.append("<div class=\"chart\"><div class=\"chart-plot\">");
        chart.ticks().forEach(tick -> out.append("<div class=\"chart-tick\" style=\"bottom: ").append(tick.position())
                .append("%\"><span>").append(tick.value()).append("</span></div>"));
        out.append("<div class=\"chart-columns\">");
        chart.columns().forEach(column -> {
            String label = esc(column.name() + ": " + column.count());
            out.append("<div class=\"chart-col\" tabindex=\"0\" aria-label=\"").append(label)
                    .append("\" style=\"--h: ").append(column.height()).append("%\"><span class=\"chart-bar\"></span>");
            if (column.peak()) {
                out.append("<span class=\"chart-peak\">").append(column.count()).append("</span>");
            }
            out.append("<span class=\"chart-tip\">").append(label).append("</span></div>");
        });
        out.append("</div></div><div class=\"chart-labels\" aria-hidden=\"true\">");
        chart.columns().forEach(column -> out.append(Markup.tag("span", "", column.label())));
        out.append("</div></div><details class=\"fold\"><summary>Als Tabelle</summary><table class=\"compact\"><thead>"
                + "<tr><th>Monat</th><th class=\"num\">Dokumente</th></tr></thead><tbody>");
        chart.columns().forEach(column -> out.append("<tr><td>").append(esc(column.name()))
                .append("</td><td class=\"num\">").append(column.count()).append("</td></tr>"));
        return out.append("</tbody></table></details></article>").toString();
    }

    private static String inboxCard(List<Document> inbox, long count) {
        StringBuilder out = new StringBuilder("<article class=\"card\"><header class=\"card-head\"><span class=\"card-icon\">"
                + icon("inbox") + "</span><h2>Eingang</h2><span class=\"card-count\">" + count + "</span></header>");
        if (inbox.isEmpty()) {
            out.append("<p class=\"card-empty\">Der Eingang ist leer, alles ist abgelegt.</p>");
            return out.append("</article>").toString();
        }
        out.append("<ul class=\"inbox-list\">");
        for (Document document : inbox) {
            String thumb = document.hasThumbnail()
                    ? "<img src=\"dokumente/" + document.id() + "/bild?v=" + document.version() + "\" alt=\"\" loading=\"lazy\">"
                    : esc(document.fileLabel());
            String sender = document.correspondentName() != null ? document.correspondentName() : "Ohne Absendername";
            out.append("<li>").append(link("dokumente/" + document.id(), "", "<span class=\"inbox-thumb\">" + thumb
                    + "</span><span class=\"inbox-text\"><strong>" + esc(document.title()) + "</strong><span>"
                    + esc(sender) + "</span></span>")).append("</li>");
        }
        out.append("</ul><footer class=\"card-foot\">")
                .append(link("dokumente/" + inbox.getFirst().id(), "card-action", "Jetzt abarbeiten"))
                .append(link("dokumente?view=inbox", "", "Alle anzeigen")).append("</footer>");
        return out.append("</article>").toString();
    }

    /** Tortengrafik mit Legende und Tabelle, wie in der Java-Fassung. */
    static String pieCard(String title, String subtitle, Pie pie, String heading, String description, String empty) {
        return "<article class=\"card pie-card\"><header class=\"card-head\"><h2>" + esc(title)
                + "</h2><span class=\"card-sub\">" + esc(subtitle) + "</span></header>"
                + pie(pie, heading, description, empty) + "</article>";
    }

    static String pie(Pie pie, String heading, String description, String empty) {
        if (pie.slices().isEmpty()) {
            return "<p class=\"card-empty\">" + esc(empty) + "</p>";
        }
        StringBuilder out = new StringBuilder("<div class=\"pie-body\"><svg class=\"pie\" viewBox=\"0 0 320 200\" role=\"img\" "
                + "aria-label=\"" + esc(description) + " Die Zahlen stehen in der Legende.\">");
        for (Slice slice : pie.slices()) {
            if (!slice.wall().isEmpty()) {
                out.append("<path class=\"pie-wall\" style=\"--c: var(--pie-").append(slice.number()).append(")\" d=\"")
                        .append(esc(slice.wall())).append("\"/>");
            }
        }
        for (Slice slice : pie.slices()) {
            String title = "<title>" + esc(slice.label() + ": " + slice.count() + " (" + slice.percent() + " %)") + "</title>";
            String top = "<path class=\"pie-top\" style=\"--c: var(--pie-" + slice.number() + ")\" d=\"" + esc(slice.top())
                    + "\"/>";
            if (slice.url() != null) {
                String target = slice.url().startsWith("/") ? slice.url().substring(1) : slice.url();
                out.append("<a class=\"pie-slice\" href=\"").append(esc(target)).append("\" router-link aria-label=\"")
                        .append(esc(slice.label() + ": " + slice.count())).append("\">").append(title).append(top)
                        .append("</a>");
            } else {
                out.append("<g class=\"pie-slice\">").append(title).append(top).append("</g>");
            }
        }
        out.append("</svg><ul class=\"pie-legend\">");
        for (Slice slice : pie.slices()) {
            out.append("<li><i class=\"dot\" style=\"--c: var(--pie-").append(slice.number()).append(")\"></i>")
                    .append(slice.url() != null ? link(slice.url(), "", esc(slice.label())) : Markup.tag("span", "", slice.label()))
                    .append("<span class=\"pie-count\">").append(slice.count()).append("</span><span class=\"pie-percent\">")
                    .append(slice.percent()).append(" %</span></li>");
        }
        out.append("</ul></div><details class=\"fold\"><summary>Als Tabelle</summary><table class=\"compact\"><thead><tr><th>")
                .append(esc(heading)).append("</th><th class=\"num\">Dokumente</th><th class=\"num\">Anteil</th></tr></thead><tbody>");
        for (Slice slice : pie.slices()) {
            out.append("<tr><td>").append(esc(slice.label())).append("</td><td class=\"num\">").append(slice.count())
                    .append("</td><td class=\"num\">").append(slice.percent()).append(" %</td></tr>");
        }
        return out.append("</tbody></table></details>").toString();
    }

    private String activityCard(List<UserActivity> rows) {
        StringBuilder out = new StringBuilder("<article class=\"card activity-card\"><header class=\"card-head\">"
                + "<h2>Aktivitäten der Benutzer</h2><span class=\"card-sub\">Anzahl Dokumente, nur für Administratoren "
                + "sichtbar</span></header>");
        if (rows.isEmpty()) {
            return out.append("<p class=\"card-empty\">Noch keine Aktivitäten erfasst.</p></article>").toString();
        }
        out.append("<div class=\"activity-table\"><table class=\"compact\"><thead><tr><th>Benutzer</th>"
                + "<th class=\"num\">Hochgeladen</th><th class=\"num\">Eingang bearbeitet</th><th class=\"num\">Bearbeitet</th>"
                + "<th class=\"num\">Gelöscht</th><th>Zuletzt aktiv</th></tr></thead><tbody>");
        for (UserActivity row : rows) {
            out.append("<tr><td>").append(esc(row.username())).append("</td><td class=\"num\">").append(row.uploaded())
                    .append("</td><td class=\"num\">").append(row.filed()).append("</td><td class=\"num\">")
                    .append(row.edited()).append("</td><td class=\"num\">").append(row.deleted()).append("</td><td>")
                    .append(esc(fmt.dateTime(row.last()))).append("</td></tr>");
        }
        return out.append("</tbody></table></div></article>").toString();
    }

    private String storageCard(Usage usage) {
        String stand = usage.measuredAt() != null ? "Dateien gezählt am " + fmt.dateTime(usage.measuredAt())
                : "Dateien werden noch gezählt";
        StringBuilder out = new StringBuilder("<article class=\"card storage-card\"><header class=\"card-head\">"
                + "<h2>Speicherverbrauch</h2><span class=\"card-sub\">" + esc(stand)
                + ", nur für Administratoren sichtbar</span></header><div class=\"storage-rows\">");
        if (usage.drive() != null) {
            out.append("<div class=\"storage-row\"><span>Laufwerk</span><span class=\"storage-value\">")
                    .append(esc(fmt.size(usage.drive().used()) + " von " + fmt.size(usage.drive().total()) + " belegt, "
                            + fmt.size(usage.drive().free()) + " frei"))
                    .append("</span><span class=\"bar-track\"><span class=\"bar-fill\" style=\"width: ")
                    .append(usage.drive().percent()).append("%\"></span></span></div>");
        }
        String share = usage.drive() != null
                ? ", " + new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(usage.share())
                        + " % des Laufwerks"
                : "";
        out.append("<div class=\"storage-row\"><span>DocuMan</span><span class=\"storage-value\">")
                .append(esc(fmt.size(usage.total()) + share)).append("</span>");
        if (usage.drive() != null) {
            out.append("<span class=\"bar-track\"><span class=\"bar-fill\" style=\"width: ").append(usage.share())
                    .append("%\"></span></span>");
        }
        out.append("</div></div>");
        out.append(usage.measuredAt() != null
                ? Markup.tag("p", "storage-parts", "Dokumente " + fmt.size(usage.media()) + " · Datenbank "
                        + fmt.size(usage.database()) + " · Sicherungen " + fmt.size(usage.backups()))
                : Markup.tag("p", "storage-parts", "Datenbank " + fmt.size(usage.database())
                        + ", Dokumente und Sicherungen zählt der Hintergrunddienst gleich."));
        return out.append("</article>").toString();
    }

    private static String categoryCard(Category category) {
        StringBuilder out = new StringBuilder("<article class=\"card category\"><header class=\"card-head\">"
                + "<span class=\"card-icon\"><svg class=\"icon\" aria-hidden=\"true\"><use href=\"#" + esc(category.icon())
                + "\"/></svg></span>" + Markup.tag("h2", "", category.title()) + "<span class=\"card-count\">"
                + category.total() + "</span></header>");
        if (category.rows().isEmpty()) {
            out.append("<p class=\"card-empty\">Noch nichts zugeordnet.</p>");
        } else {
            out.append("<div class=\"bars\">");
            category.rows().forEach(row -> out.append(link(row.url(), "bar-row", "<span class=\"bar-label\">"
                    + dot(row.color()) + esc(row.label()) + "</span><span class=\"bar-value\">" + row.count()
                    + "</span><span class=\"bar-track\"><span class=\"bar-fill\" style=\"width: " + row.share()
                    + "%\"></span></span>")));
            out.append("</div>");
        }
        out.append("<footer class=\"card-foot\">");
        if (category.without() > 0 && category.withoutUrl() != null) {
            out.append(link(category.withoutUrl(), "", category.without() + " ohne Zuordnung"));
        } else if (category.without() > 0) {
            out.append("<span>").append(category.without()).append(" ohne Zuordnung</span>");
        } else {
            out.append("<span>Alles zugeordnet</span>");
        }
        out.append(link(category.manageUrl(), "", "Verwalten"));
        return out.append("</footer></article>").toString();
    }
}
