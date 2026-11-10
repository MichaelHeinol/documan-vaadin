package de.documan.ui;

import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datepicker.DatePicker.DatePickerI18n;

/** Datumsfelder auf Deutsch: Format TT.MM.JJJJ, Woche ab Montag. */
final class Dates {

    private Dates() {
    }

    static DatePicker picker(String label) {
        DatePicker picker = new DatePicker(label);
        picker.setLocale(Locale.GERMANY);
        DatePickerI18n i18n = new DatePickerI18n();
        i18n.setMonthNames(List.of("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September",
                "Oktober", "November", "Dezember"));
        i18n.setWeekdays(List.of("Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"));
        i18n.setWeekdaysShort(List.of("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"));
        i18n.setFirstDayOfWeek(1);
        i18n.setToday("Heute");
        i18n.setCancel("Abbrechen");
        i18n.setDateFormats("dd.MM.yyyy", "d.M.yyyy", "d.M.yy", "yyyy-MM-dd");
        i18n.setBadInputErrorMessage("Bitte ein gültiges Datum eingeben.");
        picker.setI18n(i18n);
        picker.setClearButtonVisible(true);
        return picker;
    }
}
