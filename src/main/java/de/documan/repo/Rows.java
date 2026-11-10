package de.documan.repo;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/** Lesehilfen für nullbare Spalten. */
final class Rows {

    private Rows() {
    }

    static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    static Integer nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static LocalDate date(ResultSet row, String column) throws SQLException {
        Date value = row.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
