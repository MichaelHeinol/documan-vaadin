package de.documan.service.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.documan.repo.AiRepository.TrainingRow;

/**
 * Lernende Zuordnung aus den eigenen Dokumenten: ähnliche Dokumente bestimmen Absendername, Dokumenttyp,
 * Kategorie und Tags.
 *
 * <p>Jedes Dokument wird zu einem TF-IDF-Vektor seiner Wörter. Ein neues Dokument sucht seine ähnlichsten
 * Nachbarn (Kosinus-Ähnlichkeit). Ein Wert wird nur vorgeschlagen, wenn genug ähnliche Nachbarn ihn
 * übereinstimmend tragen. Ganz neue Absender bleiben so leer, statt dem nächstbesten zugeschlagen zu werden.
 */
public final class LearnedModel {

    /** So viele Nachbarn stimmen ab. */
    static final int NEIGHBOURS = 7;
    /** Weniger ähnliche Dokumente zählen nicht. */
    static final double MIN_SIMILARITY = 0.25;
    /** Ab dieser Ähnlichkeit genügt ein einzelner Nachbar. */
    static final double STRONG_SIMILARITY = 0.7;
    /** Anteil der Stimmen, den ein Wert mindestens braucht. */
    static final double MIN_SHARE = 0.6;
    /** Anteil der Stimmen für einen Tag. */
    static final double TAG_SHARE = 0.5;
    /** Darunter lernt das Modell nichts. */
    static final int MIN_DOCUMENTS = 3;
    private static final int MAX_TOKENS = 5000;
    private static final int MAX_TERMS = 400;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,40}");

    /** Vorschlag für ein Dokument. Fehlt ein Wert, ist sich das Modell nicht sicher genug. */
    public record Prediction(Long correspondentId, Long typeId, Long categoryId, Set<Long> tagIds) {
        static final Prediction NONE = new Prediction(null, null, null, Set.of());
    }

    private record Vector(int[] terms, float[] weights) {
    }

    private record Example(long id, Vector vector, Long correspondentId, Long typeId, Long categoryId,
            Set<Long> tagIds) {
    }

    private record Neighbour(Example example, double similarity) {
    }

    private final Map<String, Integer> vocabulary;
    private final double[] idf;
    private final List<Example> examples;

    private LearnedModel(Map<String, Integer> vocabulary, double[] idf, List<Example> examples) {
        this.vocabulary = vocabulary;
        this.idf = idf;
        this.examples = examples;
    }

    public int size() {
        return examples.size();
    }

    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.GERMAN));
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            String token = matcher.group();
            // Kurze Zahlen sind meist Beträge, Seiten oder Tage. Lange Zahlen wie Kundennummern zählen.
            if (token.chars().allMatch(Character::isDigit) && token.length() < 5) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static Map<String, Integer> counts(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens(text)) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    /** Lernt aus allen Beispielen, die mindestens eine Zuordnung tragen. */
    public static LearnedModel train(List<TrainingRow> rows) {
        List<TrainingRow> labelled = rows.stream()
                .filter(row -> row.correspondentId() != null || row.typeId() != null || row.categoryId() != null
                        || !row.tagIds().isEmpty())
                .toList();
        List<Map<String, Integer>> documentCounts = labelled.stream().map(row -> counts(row.content())).toList();
        Map<String, Integer> frequency = new HashMap<>();
        documentCounts.forEach(counts -> counts.keySet().forEach(term -> frequency.merge(term, 1, Integer::sum)));
        Map<String, Integer> vocabulary = new HashMap<>();
        // Wörter aus nur einem Dokument helfen beim Vergleichen nicht.
        frequency.forEach((term, documents) -> {
            if (documents >= 2 || labelled.size() < 10) {
                vocabulary.put(term, vocabulary.size());
            }
        });
        double[] idf = new double[vocabulary.size()];
        vocabulary.forEach((term, index) -> idf[index] = Math.log((1.0 + labelled.size())
                / (1.0 + frequency.get(term))) + 1.0);
        LearnedModel empty = new LearnedModel(vocabulary, idf, List.of());
        List<Example> examples = new ArrayList<>();
        for (int position = 0; position < labelled.size(); position++) {
            TrainingRow row = labelled.get(position);
            Vector vector = empty.vector(documentCounts.get(position));
            if (vector.terms().length > 0) {
                examples.add(new Example(row.id(), vector, row.correspondentId(), row.typeId(), row.categoryId(),
                        row.tagIds()));
            }
        }
        return new LearnedModel(vocabulary, idf, List.copyOf(examples));
    }

    private Vector vector(Map<String, Integer> counts) {
        Map<Integer, Double> weights = new HashMap<>();
        counts.forEach((term, count) -> {
            Integer index = vocabulary.get(term);
            if (index != null) {
                weights.put(index, (1.0 + Math.log(count)) * idf[index]);
            }
        });
        List<Map.Entry<Integer, Double>> strongest = weights.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()).limit(MAX_TERMS).toList();
        double norm = Math.sqrt(strongest.stream().mapToDouble(entry -> entry.getValue() * entry.getValue()).sum());
        int[] terms = new int[strongest.size()];
        float[] values = new float[strongest.size()];
        for (int position = 0; position < strongest.size(); position++) {
            terms[position] = strongest.get(position).getKey();
            values[position] = (float) (strongest.get(position).getValue() / norm);
        }
        return new Vector(terms, values);
    }

    private static double similarity(Map<Integer, Float> query, Vector other) {
        double sum = 0;
        for (int position = 0; position < other.terms().length; position++) {
            Float weight = query.get(other.terms()[position]);
            if (weight != null) {
                sum += weight * other.weights()[position];
            }
        }
        return sum;
    }

    /** Vorschlag für einen Text. Das Dokument selbst (exceptId) zählt nicht als Nachbar. */
    public Prediction predict(String text, long exceptId) {
        if (examples.size() < MIN_DOCUMENTS) {
            return Prediction.NONE;
        }
        List<Neighbour> neighbours = neighbours(text, exceptId, NEIGHBOURS);
        if (neighbours.isEmpty()) {
            return Prediction.NONE;
        }
        return new Prediction(vote(neighbours, Example::correspondentId), vote(neighbours, Example::typeId),
                vote(neighbours, Example::categoryId), tags(neighbours));
    }

    /**
     * Die ähnlichsten zugeordneten Dokumente, das ähnlichste zuerst. Das Sprachmodell bekommt sie als Beispiele,
     * wie in diesem Archiv abgelegt wird.
     */
    public List<Long> similar(String text, long exceptId, int limit) {
        return neighbours(text, exceptId, limit).stream().map(neighbour -> neighbour.example().id()).toList();
    }

    private List<Neighbour> neighbours(String text, long exceptId, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Vector vector = vector(counts(text));
        if (vector.terms().length == 0) {
            return List.of();
        }
        Map<Integer, Float> query = new HashMap<>();
        for (int position = 0; position < vector.terms().length; position++) {
            query.put(vector.terms()[position], vector.weights()[position]);
        }
        return examples.stream()
                .filter(example -> example.id() != exceptId)
                .map(example -> new Neighbour(example, similarity(query, example.vector())))
                .filter(neighbour -> neighbour.similarity() >= MIN_SIMILARITY)
                .sorted(Comparator.comparingDouble(Neighbour::similarity).reversed())
                .limit(limit)
                .toList();
    }

    /** Der Wert mit den meisten gewichteten Stimmen, wenn er klar genug vorn liegt. */
    private static Long vote(List<Neighbour> neighbours, Function<Example, Long> label) {
        Map<Long, Double> weights = new HashMap<>();
        Map<Long, Integer> supporters = new HashMap<>();
        double total = 0;
        double strongest = 0;
        for (Neighbour neighbour : neighbours) {
            Long value = label.apply(neighbour.example());
            if (value == null) {
                continue;
            }
            weights.merge(value, neighbour.similarity(), Double::sum);
            supporters.merge(value, 1, Integer::sum);
            total += neighbour.similarity();
        }
        if (weights.isEmpty()) {
            return null;
        }
        Map.Entry<Long, Double> best = weights.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        for (Neighbour neighbour : neighbours) {
            if (best.getKey().equals(label.apply(neighbour.example()))) {
                strongest = Math.max(strongest, neighbour.similarity());
            }
        }
        boolean enough = supporters.get(best.getKey()) >= 2 || strongest >= STRONG_SIMILARITY;
        return best.getValue() / total >= MIN_SHARE && enough ? best.getKey() : null;
    }

    /** Tags, die die Mehrheit der ähnlichen, verschlagworteten Dokumente trägt. */
    private static Set<Long> tags(List<Neighbour> neighbours) {
        List<Neighbour> tagged = neighbours.stream().filter(neighbour -> !neighbour.example().tagIds().isEmpty())
                .toList();
        double total = tagged.stream().mapToDouble(Neighbour::similarity).sum();
        Map<Long, Double> weights = new HashMap<>();
        Map<Long, Integer> supporters = new HashMap<>();
        Map<Long, Double> strongest = new HashMap<>();
        for (Neighbour neighbour : tagged) {
            for (long tag : neighbour.example().tagIds()) {
                weights.merge(tag, neighbour.similarity(), Double::sum);
                supporters.merge(tag, 1, Integer::sum);
                strongest.merge(tag, neighbour.similarity(), Math::max);
            }
        }
        Set<Long> chosen = new LinkedHashSet<>();
        weights.forEach((tag, weight) -> {
            boolean enough = supporters.get(tag) >= 2 || strongest.get(tag) >= STRONG_SIMILARITY;
            if (total > 0 && weight / total >= TAG_SHARE && enough) {
                chosen.add(tag);
            }
        });
        return chosen;
    }
}
