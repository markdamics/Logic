package com.logic.analyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every API request requires HTTP Basic auth by default. There's no login UI - the
 * browser's native credential prompt is enough for a single-admin, self-hosted tool.
 * Auth can be turned off entirely via app.auth.enabled (e.g. for a trusted-network
 * deployment already sitting behind its own access control).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled,
                                                     @Value("${app.auth.enabled:true}") boolean authEnabled) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (authEnabled) {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
        } else {
            log.warn("app.auth.enabled=false - authentication is DISABLED. Every API endpoint is open to " +
                    "anyone who can reach this instance; only do this behind your own access control.");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        if (h2ConsoleEnabled) {
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Single admin user, credentials from app.admin.username/password in application.yml
     * (itself overridable via ADMIN_USERNAME/ADMIN_PASSWORD env vars). Falls back to
     * admin/admin with a loud startup warning so local dev keeps working with zero setup -
     * self-hosters exposing this beyond localhost must override the password. Not created
     * at all when app.auth.enabled=false.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true", matchIfMissing = true)
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder,
                                                  @Value("${app.admin.username:admin}") String adminUsername,
                                                  @Value("${app.admin.password:}") String adminPassword) {
        if (adminPassword == null || adminPassword.isBlank()) {
            adminPassword = DEFAULT_ADMIN_PASSWORD;
            log.warn("app.admin.password is not set - using default credentials ({}/{}). " +
                            "Set it (or ADMIN_PASSWORD) before exposing this instance to any network you don't fully trust.",
                    adminUsername, DEFAULT_ADMIN_PASSWORD);
        } else {
            log.info("Admin credentials configured from app.admin.username/password.");
        }

        var user = User.withUsername(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
