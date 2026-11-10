package de.documan.service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import de.documan.domain.Algorithm;
import de.documan.domain.MasterItem;

/** Prüft, ob eine Zuordnungsregel auf einen Text passt. */
public final class Matching {

    private static final Pattern TERM = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;

    private Matching() {
    }

    static List<String> terms(String pattern) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TERM.matcher(pattern);
        while (matcher.find()) {
            terms.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return terms;
    }

    private static OptionalInt wordPosition(String term, int flags, String text) {
        return position(Pattern.compile("(?<!\\w)" + Pattern.quote(term) + "(?!\\w)", flags), text);
    }

    private static OptionalInt position(Pattern pattern, String text) {
        try {
            Matcher matcher = pattern.matcher(text);
            return matcher.find() ? OptionalInt.of(matcher.start()) : OptionalInt.empty();
        } catch (StackOverflowError tooComplex) {
            // Javas Regex-Engine arbeitet rekursiv, lange Texte dürfen den Worker nicht stoppen.
            return OptionalInt.empty();
        }
    }

    /** Fundstelle der Regel im Text oder leer, wenn sie nicht greift. */
    public static OptionalInt matchPosition(MasterItem rule, String text) {
        String pattern = rule.match().strip();
        if (rule.algorithm() == Algorithm.NONE || pattern.isEmpty()) {
            return OptionalInt.empty();
        }
        int flags = UNICODE | (rule.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);

        if (rule.algorithm() == Algorithm.LITERAL) {
            return wordPosition(pattern, flags, text);
        }
        if (rule.algorithm() == Algorithm.REGEX) {
            try {
                return position(Pattern.compile(pattern, flags), text);
            } catch (PatternSyntaxException invalid) {
                return OptionalInt.empty();
            }
        }

        List<OptionalInt> positions = terms(pattern).stream().map(term -> wordPosition(term, flags, text)).toList();
        boolean missing = positions.stream().anyMatch(OptionalInt::isEmpty);
        if (positions.isEmpty() || (rule.algorithm() == Algorithm.ALL && missing)) {
            return OptionalInt.empty();
        }
        return positions.stream().filter(OptionalInt::isPresent).mapToInt(OptionalInt::getAsInt).min();
    }

    /**
     * Der früheste Treffer gewinnt: Der Absender steht fast immer oben im Brief,
     * genannte Dritte (z. B. die Bank im Zahlungshinweis) weiter unten.
     */
    public static MasterItem firstMatch(List<MasterItem> rules, String text) {
        MasterItem best = null;
        int bestPosition = Integer.MAX_VALUE;
        for (MasterItem rule : rules) {
            OptionalInt position = matchPosition(rule, text);
            if (position.isPresent() && position.getAsInt() < bestPosition) {
                best = rule;
                bestPosition = position.getAsInt();
            }
        }
        return best;
    }
}
