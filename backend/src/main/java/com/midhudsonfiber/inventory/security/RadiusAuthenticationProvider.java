package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.RadiusSettings;
import com.midhudsonfiber.inventory.repo.RadiusSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Signs people in against RADIUS/NPS, replacing the LDAP and Active Directory
 * providers removed in V26.
 *
 * <p>Three things about this are deliberate.
 *
 * <p><b>Settings are read on every attempt</b>, not captured when the bean is
 * built. That is the entire reason this moved out of environment variables and
 * into Settings &gt; RADIUS: switching it on, correcting a typo in the host, or
 * turning it off during an incident has to take effect now, without a restart.
 *
 * <p><b>Local accounts are tried first</b> and are unaffected by anything here.
 * {@code DaoAuthenticationProvider} is ahead of this one in the chain, so a
 * misconfigured or unreachable RADIUS server can never lock an administrator out
 * of their own local account -- which is the account they would need to fix it.
 *
 * <p><b>An unreachable server is not a rejection.</b> It throws
 * {@link AuthenticationServiceException} rather than returning null or throwing
 * BadCredentials, so the sign-in screen can say the server is unreachable
 * instead of telling somebody with a perfectly good password that it is wrong.
 * The distinction matters at 3am.
 *
 * <p>Walking the configured servers, and deciding that a reject stops the walk
 * while a timeout does not, is {@link RadiusClientRunner}'s job -- shared with
 * the settings screen's test button so the two cannot drift apart.
 */
@Component
public class RadiusAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(RadiusAuthenticationProvider.class);

    private final RadiusSettingsRepository settings;
    private final RadiusClientRunner radius;
    private final ExternalUserProvisioner provisioner;
    private final PermissionResolver permissions;

    public RadiusAuthenticationProvider(RadiusSettingsRepository settings,
                                        RadiusClientRunner radius,
                                        ExternalUserProvisioner provisioner,
                                        PermissionResolver permissions) {
        this.settings = settings;
        this.radius = radius;
        this.provisioner = provisioner;
        this.permissions = permissions;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        RadiusSettings config = settings.current();
        // Not configured is not an error: it is the default state, and the local
        // provider ahead of this one has already had its say. Returning null
        // means "not my business", which is exactly right.
        if (!config.isEnabled()) {
            return null;
        }

        String username = authentication.getName();
        String password = authentication.getCredentials() == null
                ? null : authentication.getCredentials().toString();
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            // An empty password is an Access-Request every RADIUS server rejects,
            // so this only avoids a pointless round trip.
            throw new BadCredentialsException("Bad credentials");
        }

        RadiusClientRunner.Attempt attempt = radius.authenticate(config, username, password);
        switch (attempt.outcome()) {
            case REJECTED -> throw new BadCredentialsException("Bad credentials");
            case UNREACHABLE -> throw new AuthenticationServiceException(
                    "No RADIUS server answered. " + attempt.detail());
            case NOT_CONFIGURED -> {
                // Switched on with nothing usable behind it: a deployment fault,
                // not a bad password. Saying so is the only way anybody finds it,
                // because the sign-in screen looks identical otherwise.
                log.error("RADIUS sign-in is enabled but unusable: {}", attempt.detail());
                throw new AuthenticationServiceException("RADIUS is not configured correctly.");
            }
            case ACCEPTED -> { /* fall through */ }
        }

        AppUser user = provisioner.provision(username, AppUser.AuthProvider.RADIUS, null, null);
        if (!user.isActive()) {
            // Disabling an account has to hold even though the password was
            // accepted upstream: NPS knows nothing about this application's idea
            // of a disabled user.
            throw new BadCredentialsException("Bad credentials");
        }

        AppUserPrincipal principal = new AppUserPrincipal(user, permissions.resolve(user));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
