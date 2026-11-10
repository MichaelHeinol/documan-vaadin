package de.documan.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.AppUser;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AppUser map(ResultSet row) throws SQLException {
        return new AppUser(row.getLong("id"), row.getString("username"), row.getString("email"),
                row.getString("password_hash"), row.getBoolean("superuser"), row.getBoolean("active"),
                Rows.instant(row, "created"), Rows.instant(row, "last_login"), row.getString("first_name"),
                row.getString("last_name"), Rows.instant(row, "pending_since"));
    }

    public List<AppUser> all() {
        return jdbc.sql("SELECT * FROM app_user ORDER BY username").query((row, index) -> map(row)).list();
    }

    public Optional<AppUser> find(long id) {
        return jdbc.sql("SELECT * FROM app_user WHERE id = ?").param(id).query((row, index) -> map(row)).optional();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.sql("SELECT * FROM app_user WHERE username = ?")
                .param(username).query((row, index) -> map(row)).optional();
    }

    public boolean isEmpty() {
        return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single() == 0;
    }

    public long countActiveAdminsExcept(long id) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE superuser AND active AND id <> ?")
                .param(id).query(Long.class).single();
    }

    public long insert(String username, String passwordHash, boolean superuser, boolean active, String email) {
        return jdbc.sql("""
                INSERT INTO app_user (username, password_hash, superuser, active, email) VALUES (?, ?, ?, ?, ?) RETURNING id
                """)
                .params(username, passwordHash, superuser, active, email)
                .query(Long.class).single();
    }

    /** Noch inaktiv: Erst der Link aus der Bestätigungsmail schaltet das Konto frei. */
    public long insertPending(String username, String passwordHash, String email, String firstName, String lastName) {
        return jdbc.sql("""
                INSERT INTO app_user (username, password_hash, superuser, active, email, first_name, last_name, pending_since)
                VALUES (?, ?, false, false, ?, ?, ?, now()) RETURNING id
                """)
                .params(username, passwordHash, email, firstName, lastName)
                .query(Long.class).single();
    }

    /** Aktive Konten zu einer Adresse. Wartende Registrierungen haben noch kein Passwort. */
    public List<AppUser> activeByEmail(String email) {
        return jdbc.sql("SELECT * FROM app_user WHERE lower(email) = lower(?) AND active AND pending_since IS NULL")
                .param(email.strip()).query((row, index) -> map(row)).list();
    }

    public boolean emailInUse(String email) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE lower(email) = lower(?)")
                .param(email).query(Long.class).single() > 0;
    }

    public void confirmPending(long id) {
        jdbc.sql("UPDATE app_user SET active = true, pending_since = NULL WHERE id = ?").param(id).update();
    }

    /** Macht Name und Adresse nicht bestätigter Registrierungen wieder frei. */
    public void deleteExpiredPending(Instant before) {
        jdbc.sql("DELETE FROM app_user WHERE pending_since < ? AND NOT active").param(Timestamp.from(before)).update();
    }

    /** Schaltet ein Superuser ein wartendes Konto frei, ist die Bestätigung erledigt. */
    public void update(long id, String username, boolean superuser, boolean active, String email) {
        jdbc.sql("""
                UPDATE app_user SET username = ?, superuser = ?, active = ?, email = ?,
                    pending_since = CASE WHEN ? THEN NULL ELSE pending_since END
                WHERE id = ?
                """)
                .params(username, superuser, active, email, active, id).update();
    }

    /** Wie DocuMan für einen Benutzer aussieht. */
    public record Appearance(String look, String mode) {

        public static final Appearance DEFAULT = new Appearance("standard", "auto");
    }

    public Appearance appearance(String username) {
        return jdbc.sql("SELECT look, mode FROM app_user WHERE username = ?").param(username)
                .query((row, index) -> new Appearance(row.getString("look"), row.getString("mode")))
                .optional().orElse(Appearance.DEFAULT);
    }

    public void setAppearance(long id, String look, String mode) {
        jdbc.sql("UPDATE app_user SET look = ?, mode = ? WHERE id = ?").params(look, mode, id).update();
    }

    public void setNames(long id, String firstName, String lastName) {
        jdbc.sql("UPDATE app_user SET first_name = ?, last_name = ? WHERE id = ?")
                .params(firstName, lastName, id).update();
    }

    public void setEmail(long id, String email) {
        jdbc.sql("UPDATE app_user SET email = ? WHERE id = ?").params(email, id).update();
    }

    public void setPassword(long id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public void touchLogin(String username) {
        jdbc.sql("UPDATE app_user SET last_login = now() WHERE username = ?").param(username).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM app_user WHERE id = ?").param(id).update();
    }
}
