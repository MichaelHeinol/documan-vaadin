package de.documan.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import de.documan.domain.Document;
import de.documan.repo.DocumentRepository;
import de.documan.repo.Selection;

/** Volltextsuche auf Basis von PostgreSQL. */
@Service
public class SearchService {

    /** Ein Ausdruck für den tsquery-Wert und sein einziger Parameter. */
    public record TsQuery(String sql, String param) {
    }

    /** Ergebnis der Suche: die Treffer als Auswahl und die Relevanz als Sortierung. */
    public record Outcome(Selection selection, String order, List<Object> orderParams) {
    }

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern WORD = Pattern.compile("\\w+", FLAGS);
    private static final Pattern OPERATORS = Pattern.compile("[\"-]|\\b(or|oder)\\b", FLAGS);
    private static final Pattern GERMAN_OR = Pattern.compile("\\boder\\b", FLAGS);
    // Eigene Marker, damit der Dokumenttext vor der Ausgabe vollständig escaped werden kann.
    private static final String MARK_START = "";
    private static final String MARK_END = "";
    private static final String HEADLINE_OPTIONS = "StartSel=" + MARK_START + ", StopSel=" + MARK_END
            + ", MaxWords=32, MinWords=12, MaxFragments=2, FragmentDelimiter=\" … \"";
    private static final int MIN_SUBSTRING = 3;

    private final JdbcClient jdbc;
    private final DocumentRepository documents;

    public SearchService(JdbcClient jdbc, DocumentRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    private static List<String> words(String text) {
        return WORD.matcher(text).results().map(match -> match.group()).toList();
    }

    /**
     * Einfache Eingaben suchen wortanfangsweise (auch beim Tippen), Anführungszeichen,
     * -ausschluss und „oder“ gehen an die websearch-Syntax.
     */
    public static TsQuery buildQuery(String text) {
        if (OPERATORS.matcher(text).find()) {
            return new TsQuery("websearch_to_tsquery('german', ?)", GERMAN_OR.matcher(text).replaceAll("or"));
        }
        String prefixes = words(text).stream().map(word -> word + ":*").collect(Collectors.joining(" & "));
        return new TsQuery("to_tsquery('german', ?)", prefixes);
    }

    /**
     * Deutsche Komposita: „rechnung“ soll auch „Stromrechnung“ finden.
     *
     * <p>Die Stammformsuche zerlegt keine zusammengesetzten Wörter. Die Teilstringsuche
     * nutzt die Trigramm-Indizes auf Titel und Text.
     */
    private static Selection substringFilter(Selection selection, List<String> words) {
        Selection result = selection;
        for (String word : words) {
            String pattern = "%" + word.replace("_", "\\_") + "%";
            result = result.and("(d.content ILIKE ? OR d.title ILIKE ?)", pattern, pattern);
        }
        return result;
    }

    /** Filtert nach dem Suchtext und sortiert nach Relevanz. Ohne Suchwörter bleibt alles, wie es ist. */
    public Outcome search(Selection selection, String text) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return new Outcome(selection, null, List.of());
        }
        TsQuery query = buildQuery(text);
        // Getrennt abfragen statt mit OR: Bei OR verschätzt sich PostgreSQL und liest alle Texte.
        // Die ermittelten IDs ersparen auch Trefferzahlen und Filtern den erneuten Suchlauf.
        Set<Long> matches = new LinkedHashSet<>(
                documents.ids(selection.and("d.search_vector @@ " + query.sql(), query.param())));
        // Kürzere Wörter ergeben keine Trigramme und würden alle Texte durchlaufen.
        boolean longEnough = words.stream().allMatch(word -> word.codePointCount(0, word.length()) >= MIN_SUBSTRING);
        if (!OPERATORS.matcher(text).find() && longEnough) {
            matches.addAll(documents.ids(substringFilter(selection, words)));
        }
        Selection found = Selection.ALL.and("d.id = ANY(?)", (Object) matches.toArray(Long[]::new));
        return new Outcome(found, "ts_rank(d.search_vector, " + query.sql() + ") DESC, d.added DESC, d.id DESC",
                List.of(query.param()));
    }

    /**
     * Ergänzt die Dokumente einer Ergebnisseite um hervorgehobene Textausschnitte.
     *
     * <p>ts_headline ist teuer und läuft deshalb nur für die angezeigte Seite.
     */
    public List<Document> addSnippets(List<Document> page, String text) {
        if (words(text).isEmpty() || page.isEmpty()) {
            return page;
        }
        TsQuery query = buildQuery(text);
        Long[] ids = page.stream().map(Document::id).toArray(Long[]::new);
        List<Object> params = new ArrayList<>(List.of(query.param(), HEADLINE_OPTIONS));
        params.add(ids);
        Map<Long, String> headlines = jdbc
                .sql("SELECT d.id, ts_headline('german', d.content, " + query.sql() + ", ?) AS snippet "
                        + "FROM document d WHERE d.id = ANY(?)")
                .params(params)
                .query((row, index) -> Map.entry(row.getLong("id"), row.getString("snippet")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Function<Document, Document> withSnippet =
                document -> document.withSnippet(markUp(headlines.getOrDefault(document.id(), "")));
        return page.stream().map(withSnippet).toList();
    }

    private static String markUp(String headline) {
        return HtmlUtils.htmlEscape(headline, "UTF-8")
                .replace(MARK_START, "<mark>")
                .replace(MARK_END, "</mark>");
    }
}
