package de.documan.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.documan.repo.MasterRepository.CategoryNode;

/**
 * Die Stufen der Kategorien, bis zu {@link #MAX_LEVELS} tief. Es gibt nur wenige Kategorien, deshalb
 * lädt DocuMan sie bei Bedarf ganz und rechnet Pfade und Summen hier statt in SQL.
 */
public final class CategoryPaths {

    public static final int MAX_LEVELS = 10;
    public static final String SEPARATOR = " / ";

    private final Map<Long, CategoryNode> nodes = new LinkedHashMap<>();
    private final Map<Long, List<Long>> children = new LinkedHashMap<>();

    public CategoryPaths(List<CategoryNode> categories) {
        categories.forEach(node -> nodes.put(node.id(), node));
        for (CategoryNode node : categories) {
            Long parent = nodes.containsKey(node.parentId()) ? node.parentId() : null;
            children.computeIfAbsent(parent, key -> new ArrayList<>()).add(node.id());
        }
        Comparator<Long> byName = Comparator.comparing(id -> nodes.get(id).name(), String.CASE_INSENSITIVE_ORDER);
        children.values().forEach(list -> list.sort(byName));
    }

    public boolean contains(Long id) {
        return id != null && nodes.containsKey(id);
    }

    public String name(long id) {
        return nodes.get(id).name();
    }

    /** Die übergeordnete Kategorie, null auf der obersten Stufe. */
    public Long parent(long id) {
        Long parent = nodes.get(id).parentId();
        return nodes.containsKey(parent) ? parent : null;
    }

    /** Die Namen von der obersten Stufe bis zu dieser Kategorie. Leer ohne Kategorie. */
    public List<String> path(Long id) {
        List<String> names = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long current = id; contains(current) && seen.add(current); current = parent(current)) {
            names.add(name(current));
        }
        Collections.reverse(names);
        return names;
    }

    /** Zerlegt einen eingetippten Pfad wie „Finanzen / Bank“ in seine Stufen. Leere Stufen entfallen. */
    public static List<String> parse(String text) {
        List<String> names = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("/")) {
            String name = part.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Pfad zum Anzeigen, etwa „Finanzen / Bank“. */
    public String label(Long id) {
        return String.join(SEPARATOR, path(id));
    }

    /** Die Stufe einer Kategorie, die oberste ist 1. */
    public int depth(long id) {
        return path(id).size();
    }

    /** Wie viele Stufen diese Kategorie samt ihren Unterkategorien belegt, mindestens 1. */
    public int height(long id) {
        int below = 0;
        for (long child : children(id)) {
            below = Math.max(below, height(child));
        }
        return below + 1;
    }

    public List<Long> children(Long id) {
        return children.getOrDefault(id, List.of());
    }

    public List<Long> roots() {
        return children(null);
    }

    /** Die Kategorie selbst und alle darunter. */
    public Set<Long> withDescendants(long id) {
        Set<Long> all = new HashSet<>();
        List<Long> open = new ArrayList<>(List.of(id));
        while (!open.isEmpty()) {
            long current = open.removeLast();
            if (all.add(current)) {
                open.addAll(children(current));
            }
        }
        return all;
    }

    /** Die oberste Stufe über einer Kategorie. */
    public long root(long id) {
        long current = id;
        Set<Long> seen = new HashSet<>();
        while (parent(current) != null && seen.add(current)) {
            current = parent(current);
        }
        return current;
    }

    /** Alle Kategorien in Baumreihenfolge, jede mit ihrem Pfad. Für Auswahllisten. */
    public List<Map.Entry<Long, String>> ordered() {
        List<Map.Entry<Long, String>> result = new ArrayList<>();
        addOrdered(null, result, new HashSet<>());
        return result;
    }

    private void addOrdered(Long parent, List<Map.Entry<Long, String>> result, Set<Long> seen) {
        for (long id : children(parent)) {
            if (seen.add(id)) {
                result.add(Map.entry(id, label(id)));
                addOrdered(id, result, seen);
            }
        }
    }

    /** Zählt Dokumente je Kategorie samt allen Unterkategorien. direct zählt nur die eigenen Typen. */
    public Map<Long, Long> totals(Map<Long, Long> direct) {
        Map<Long, Long> totals = new LinkedHashMap<>();
        direct.forEach((id, count) -> {
            Set<Long> seen = new HashSet<>();
            for (Long current = id; contains(current) && seen.add(current); current = parent(current)) {
                totals.merge(current, count, Long::sum);
            }
        });
        return totals;
    }
}
