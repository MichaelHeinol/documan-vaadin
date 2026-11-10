package de.documan.ui;

import com.vaadin.flow.component.HasValidation;

/** Fehlermeldungen an Eingabefeldern. */
final class Forms {

    private Forms() {
    }

    /** Zeigt problem am Feld, null nimmt die Meldung weg. Gibt zurück, ob das Feld in Ordnung ist. */
    static boolean show(HasValidation field, String problem) {
        field.setErrorMessage(problem == null ? "" : problem);
        field.setInvalid(problem != null);
        return problem == null;
    }
}
