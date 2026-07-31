package com.midhudsonfiber.inventory.config;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.security.AppUserDetailsService;
import com.midhudsonfiber.inventory.security.DirectoryUserProvisioner;
import com.midhudsonfiber.inventory.security.JitUserDetailsContextMapper;
import com.midhudsonfiber.inventory.security.PermissionResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.ArrayList;
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
     * Authentication is core, synchronous, always-on functionality and is kept
     * strictly separate from the Plugin Framework, even though LDAP/AD appear in
     * both. The providers here never participate in "a plugin may fail safely"
     * semantics, and the plugin-side directory sync never touches credentials.
     */
    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder,
                                                       AppProperties props,
                                                       DirectoryUserProvisioner provisioner,
                                                       PermissionResolver permissionResolver) {
        List<org.springframework.security.authentication.AuthenticationProvider> providers = new ArrayList<>();

        DaoAuthenticationProvider local = new DaoAuthenticationProvider(userDetailsService);
        local.setPasswordEncoder(passwordEncoder);
        providers.add(local);

        AppProperties.Ldap ldap = props.getLdap();
        if (ldap.isEnabled() && !ldap.getUrl().isBlank()) {
            DefaultSpringSecurityContextSource contextSource =
                    new DefaultSpringSecurityContextSource(ldap.getUrl());
            if (!ldap.getBindDn().isBlank()) {
                contextSource.setUserDn(ldap.getBindDn());
                contextSource.setPassword(ldap.getBindPassword());
            }
            contextSource.afterPropertiesSet();

            // Search-then-bind: find the DN with the service account, then bind as the user.
            FilterBasedLdapUserSearch search =
                    new FilterBasedLdapUserSearch(ldap.getUserSearchBase(), ldap.getUserSearchFilter(), contextSource);
            BindAuthenticator authenticator = new BindAuthenticator(contextSource);
            authenticator.setUserSearch(search);

            LdapAuthenticationProvider ldapProvider = new LdapAuthenticationProvider(authenticator);
            ldapProvider.setUserDetailsContextMapper(
                    new JitUserDetailsContextMapper(provisioner, permissionResolver, AppUser.AuthProvider.LDAP));
            providers.add(ldapProvider);
        }

        AppProperties.ActiveDirectory ad = props.getActiveDirectory();
        if (ad.isEnabled() && !ad.getUrl().isBlank()) {
            ActiveDirectoryLdapAuthenticationProvider adProvider =
                    new ActiveDirectoryLdapAuthenticationProvider(ad.getDomain(), ad.getUrl());
            adProvider.setUserDetailsContextMapper(
                    new JitUserDetailsContextMapper(provisioner, permissionResolver, AppUser.AuthProvider.ACTIVE_DIRECTORY));
            adProvider.setConvertSubErrorCodesToExceptions(true);
            providers.add(adProvider);
        }

        return new ProviderManager(providers);
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
