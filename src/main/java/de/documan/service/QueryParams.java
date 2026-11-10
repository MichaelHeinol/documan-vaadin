package de.documan.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.util.UriUtils;

/** Die Parameter der aktuellen Adresse und daraus abgeleitete Links. */
public final class QueryParams {

    // Mehrere Tags schränken weiter ein, alle anderen Filter ersetzen den vorherigen Wert.
    private static final Set<String> MULTI_VALUE = Set.of("tag");

    private final Map<String, List<String>> values;

    public QueryParams(Map<String, String[]> parameters) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        parameters.forEach((name, list) -> copy.put(name, List.of(list)));
        this.values = copy;
    }

    private QueryParams(LinkedHashMap<String, List<String>> values) {
        this.values = values;
    }

    public List<String> values(String name) {
        return values.getOrDefault(name, List.of());
    }

    public String first(String name) {
        List<String> list = values(name);
        return list.isEmpty() ? "" : list.getFirst();
    }

    /** Alle Parameter außer den genannten, für versteckte Felder im Suchformular. */
    public Map<String, List<String>> except(String... names) {
        Map<String, List<String>> rest = new LinkedHashMap<>(values);
        List.of(names).forEach(rest::remove);
        return rest;
    }

    private static String encode(Map<String, List<String>> params) {
        return params.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(value -> UriUtils.encode(entry.getKey(),
                        StandardCharsets.UTF_8) + "=" + UriUtils.encode(value, StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));
    }

    private static String link(Map<String, List<String>> params) {
        params.remove("page");
        return "?" + encode(params);
    }

    private LinkedHashMap<String, List<String>> copy() {
        return new LinkedHashMap<>(values);
    }

    public String without(String name) {
        Map<String, List<String>> params = copy();
        params.remove(name);
        return link(params);
    }

    public String without(String name, String value) {
        Map<String, List<String>> params = copy();
        List<String> kept = values(name).stream().filter(item -> !item.equals(value)).toList();
        if (kept.isEmpty()) {
            params.remove(name);
        } else {
            params.put(name, kept);
        }
        return link(params);
    }

    public String with(String name, String value) {
        Map<String, List<String>> params = copy();
        List<String> list = new ArrayList<>();
        if (MULTI_VALUE.contains(name)) {
            list.addAll(values(name));
        }
        list.add(value);
        params.put(name, list);
        return link(params);
    }

    /** Alle Parameter ohne die Seitenzahl, Grundlage der Blätterlinks. */
    public String withoutPage() {
        Map<String, List<String>> params = copy();
        params.remove("page");
        return encode(params);
    }

    public String encoded() {
        return encode(values);
    }
}
