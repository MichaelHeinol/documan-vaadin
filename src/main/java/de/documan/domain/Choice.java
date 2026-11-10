package de.documan.domain;

import java.util.Arrays;
import java.util.Optional;

/** Auswahlwert mit Speicherwert und deutscher Bezeichnung. */
public interface Choice {

    String value();

    String label();

    static <E extends Enum<E> & Choice> E of(Class<E> type, String value) {
        return find(type, value).orElseThrow(() -> new IllegalArgumentException(value));
    }

    static <E extends Enum<E> & Choice> Optional<E> find(Class<E> type, String value) {
        return Arrays.stream(type.getEnumConstants()).filter(item -> item.value().equals(value)).findFirst();
    }
}
