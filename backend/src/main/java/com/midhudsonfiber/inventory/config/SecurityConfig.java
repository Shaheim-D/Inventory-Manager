package com.midhudsonfiber.inventory.config;

import com.midhudsonfiber.inventory.security.AppUserDetailsService;
import com.midhudsonfiber.inventory.security.RadiusAuthenticationProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** BCrypt cost 12, per MOP Part 3. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Local accounts first, then RADIUS.
     *
     * <p>The order is the safety property. {@code DaoAuthenticationProvider} is
     * ahead of RADIUS, so a RADIUS server that is unreachable, misconfigured, or
     * pointed at the wrong host can never lock an administrator out of the local
     * account they would need to fix it. Losing network sign-in is an incident;
     * losing every way in is an outage.
     *
     * <p>Authentication is core, synchronous, always-on functionality and is kept
     * strictly separate from the Plugin Framework. Nothing here participates in
     * "a plugin may fail safely" semantics, and no plugin touches credentials.
     *
     * <p>RADIUS is configured in Settings &gt; RADIUS rather than in environment
     * variables, so its provider reads the database on every attempt instead of
     * being wired up here from properties read once at startup. That is why this
     * method no longer branches on whether it is enabled -- the provider itself
     * answers "not my business" when it is not.
     */
    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder,
                                                       RadiusAuthenticationProvider radius) {
        DaoAuthenticationProvider local = new DaoAuthenticationProvider(userDetailsService);
        local.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(List.of(local, radius));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // makes the token available to the SPA on first request

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                // Branding is readable unauthenticated so the login screen can show
                // the organization's own logo before anyone has signed in.
                .requestMatchers(HttpMethod.GET, "/api/branding", "/api/branding/logo").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())   // static SPA assets and the client-side routes
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(SecurityConfig::unauthorized))
            .logout(logout -> logout.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable());

        return http.build();
    }

    private static void unauthorized(jakarta.servlet.http.HttpServletRequest request,
                                     HttpServletResponse response,
                                     AuthenticationException ex) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Authentication required\"}");
    }
}
