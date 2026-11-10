package de.documan.repo;

import java.util.ArrayList;
import java.util.List;

/** Unveränderliche WHERE-Bedingung auf die Dokumenttabelle mit dem Alias d. */
public final class Selection {

    public static final Selection ALL = new Selection(List.of(), List.of());

    private final List<String> conditions;
    private final List<Object> params;

    private Selection(List<String> conditions, List<Object> params) {
        this.conditions = conditions;
        this.params = params;
    }

    public Selection and(String condition, Object... values) {
        List<String> newConditions = new ArrayList<>(conditions);
        newConditions.add(condition);
        List<Object> newParams = new ArrayList<>(params);
        newParams.addAll(List.of(values));
        return new Selection(newConditions, newParams);
    }

    public String where() {
        return conditions.isEmpty() ? "TRUE" : String.join(" AND ", conditions);
    }

    public List<Object> params() {
        return params;
    }
}
