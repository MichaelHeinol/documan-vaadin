package de.documan.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import de.documan.service.backup.BackupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Sperrt die Seite, solange die Datenbank ersetzt wird.
 *
 * <p>Steht vor Sitzung und Anmeldung, weil deren Tabellen gerade ausgetauscht werden. Nach einer
 * Wiederherstellung gelten die Benutzer der Sicherung, ältere Sitzungen enden deshalb hier.
 */
@Component
@ConditionalOnWebApplication
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RestoreLockFilter extends OncePerRequestFilter {

    private static final int RETRY_SECONDS = 5;
    private static final String SESSION_STAMP = "documan.restoreStamp";
    private static final List<String> ALWAYS_AVAILABLE = List.of("/healthz", "/css/", "/fonts/", "/favicon");

    private final BackupService backup;
    private final DocuManProperties properties;

    public RestoreLockFilter(BackupService backup, DocuManProperties properties) {
        this.backup = backup;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean available = ALWAYS_AVAILABLE.stream().anyMatch(request.getRequestURI()::startsWith);
        if (backup.restoreInProgress() && !available) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader("Retry-After", String.valueOf(RETRY_SECONDS));
            response.setContentType("text/html;charset=UTF-8");
            response.getOutputStream().write(lockPage());
            return;
        }
        if (!available) {
            endOutdatedSession(request);
        }
        chain.doFilter(request, response);
    }

    private static byte[] lockPage() throws IOException {
        return new ClassPathResource("restore-lock.html").getContentAsString(StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void endOutdatedSession(HttpServletRequest request) throws IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        String current = currentStamp();
        Object known = session.getAttribute(SESSION_STAMP);
        if (known == null) {
            session.setAttribute(SESSION_STAMP, current);
        } else if (!known.equals(current)) {
            session.invalidate();
        }
    }

    private String currentStamp() throws IOException {
        Path stamp = properties.getMediaRoot().resolve(BackupService.RESTORE_STAMP);
        try {
            return Files.readString(stamp);
        } catch (NoSuchFileException noRestoreYet) {
            return "";
        }
    }
}
