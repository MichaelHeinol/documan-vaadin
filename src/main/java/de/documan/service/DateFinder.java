package de.documan.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Erkennt das Dokumentdatum in deutschem Text. */
public final class DateFinder {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januar", 1), Map.entry("jan", 1), Map.entry("februar", 2), Map.entry("feb", 2),
            Map.entry("märz", 3), Map.entry("maerz", 3), Map.entry("mär", 3), Map.entry("mar", 3),
            Map.entry("april", 4), Map.entry("apr", 4), Map.entry("mai", 5), Map.entry("juni", 6),
            Map.entry("jun", 6), Map.entry("juli", 7), Map.entry("jul", 7), Map.entry("august", 8),
            Map.entry("aug", 8), Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("oktober", 10), Map.entry("okt", 10), Map.entry("november", 11), Map.entry("nov", 11),
            Map.entry("dezember", 12), Map.entry("dez", 12));
    private static final String MONTH_NAMES = MONTHS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .collect(Collectors.joining("|"));
    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern DATE = Pattern.compile(String.join("|", List.of(
            "(?<![\\d.])(?<d1>\\d{1,2})\\.\\s?(?<m1>\\d{1,2})\\.\\s?(?<y1>\\d{4}|\\d{2})(?!\\d)",
            "(?<!\\d)(?<d2>\\d{1,2})\\.\\s*(?<m2>" + MONTH_NAMES + ")\\.?\\s+(?<y2>\\d{4})(?!\\d)",
            "(?<![\\d-])(?<y3>\\d{4})-(?<m3>\\d{2})-(?<d3>\\d{2})(?![\\d-])")), FLAGS);
    private static final Pattern PREFERRED = Pattern.compile("(datum|ausgestellt|erstellt|\\bvom)\\s*:?\\s*$", FLAGS);
    private static final Pattern IGNORED =
            Pattern.compile("(geb\\.|geboren|geburt|fällig|zahlbar|gültig|bis)\\D{0,12}$", FLAGS);
    private static final int SEARCH_LENGTH = 8000;
    private static final int CONTEXT_LENGTH = 30;
    private static final int EARLIEST_YEAR = 1970;

    private DateFinder() {
    }

    private static LocalDate toDate(Matcher match) {
        int day;
        int month;
        int year;
        if (match.group("d1") != null) {
            day = Integer.parseInt(match.group("d1"));
            month = Integer.parseInt(match.group("m1"));
            year = Integer.parseInt(match.group("y1"));
            if (year < 100) {
                year += 2000;
            }
        } else if (match.group("d2") != null) {
            day = Integer.parseInt(match.group("d2"));
            month = MONTHS.get(match.group("m2").toLowerCase(Locale.GERMAN));
            year = Integer.parseInt(match.group("y2"));
        } else {
            day = Integer.parseInt(match.group("d3"));
            month = Integer.parseInt(match.group("m3"));
            year = Integer.parseInt(match.group("y3"));
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException impossible) {
            return null;
        }
    }

    /** Bevorzugt Daten nach „Rechnungsdatum“, „Datum“ oder „vom“, sonst das erste im Text. */
    public static LocalDate find(String text, LocalDate today) {
        LocalDate first = null;
        Matcher matcher = DATE.matcher(text.substring(0, Math.min(text.length(), SEARCH_LENGTH)));
        while (matcher.find()) {
            LocalDate date = toDate(matcher);
            if (date == null || date.isAfter(today) || date.getYear() < EARLIEST_YEAR) {
                continue;
            }
            String before = text.substring(Math.max(0, matcher.start() - CONTEXT_LENGTH), matcher.start());
            if (IGNORED.matcher(before).find()) {
                continue;
            }
            if (PREFERRED.matcher(before).find()) {
                return date;
            }
            if (first == null) {
                first = date;
            }
        }
        return first;
    }
}
