package de.documan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;

@Configuration
public class AccountConfig {

    public static final String ADMIN_ROLE = "ADMIN";

    /** Kennt die offenen Anmeldungen, damit Sperren und Passwortwechsel sofort wirken. */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return username -> {
            AppUser user = users.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException(username));
            return User.withUsername(user.username())
                    .password(user.passwordHash())
                    .disabled(!user.active())
                    .authorities(user.superuser()
                            ? new String[] {"ROLE_USER", "ROLE_" + ADMIN_ROLE}
                            : new String[] {"ROLE_USER"})
                    .build();
        };
    }
}
