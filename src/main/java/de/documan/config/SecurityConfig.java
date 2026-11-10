package de.documan.config;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import de.documan.ui.LoginView;

/**
 * Anmeldung über die Vaadin-Seite /login. Welche Seiten wer sieht, steht an den Seiten selbst
 * (@PermitAll, @RolesAllowed, @AnonymousAllowed). Hier stehen nur die Adressen außerhalb von Vaadin.
 */
@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {

    // Vaadin braucht eingebettete Skripte und Stile. Push nutzt WebSocket zur eigenen Adresse.
    private static final String CONTENT_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
            + "connect-src 'self' ws: wss:; frame-src 'self'; frame-ancestors 'self'; base-uri 'self'; "
            + "form-action 'self'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessions) throws Exception {
        // Vaadin sperrt alles, was keine eigene Seite ist. Die Dateien liefert ein eigener Controller aus, sie
        // brauchen deshalb eine ausdrückliche Freigabe für angemeldete Benutzer.
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/healthz", "/css/**", "/fonts/**", "/favicon*.svg").permitAll()
                .requestMatchers("/dokumente/*/vorschau", "/dokumente/*/bild", "/dokumente/*/download/*")
                .authenticated());
        http.with(VaadinSecurityConfigurer.vaadin(), vaadin -> vaadin.loginView(LoginView.class, "/login?abgemeldet"));
        http.sessionManagement(session -> session.maximumSessions(-1).sessionRegistry(sessions).expiredUrl("/login"));
        http.headers(headers -> headers
                .frameOptions(frames -> frames.sameOrigin())
                .contentSecurityPolicy(policy -> policy.policyDirectives(CONTENT_POLICY)));
        return http.build();
    }

    /** Meldet beendete Sitzungen an die SessionRegistry. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** ALLOWED_HOSTS: Nur diese Hostnamen werden beantwortet. localhost bleibt für den Healthcheck erlaubt. */
    @Bean
    HttpFirewall httpFirewall(DocuManProperties properties) {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        if (!properties.getAllowedHosts().contains("*")) {
            Set<String> allowed = properties.getAllowedHosts().stream()
                    .map(String::strip).map(String::toLowerCase).collect(Collectors.toSet());
            allowed.add("localhost");
            allowed.add("127.0.0.1");
            firewall.setAllowedHostnames(host -> allowed.contains(host.toLowerCase()));
        }
        return firewall;
    }
}
