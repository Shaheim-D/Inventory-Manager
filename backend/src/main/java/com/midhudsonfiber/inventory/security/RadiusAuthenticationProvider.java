package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.RadiusSettings;
import com.midhudsonfiber.inventory.plugin.SecretResolver;
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
import org.tinyradius.packet.AccessRequest;
import org.tinyradius.packet.RadiusPacket;
import org.tinyradius.util.RadiusClient;

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
 * BadCredentials, so the sign-in screen can say the directory is unreachable
 * instead of telling somebody with a perfectly good password that it is wrong.
 * The distinction matters at 3am.
 */
@Component
public class RadiusAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(RadiusAuthenticationProvider.class);

    private final RadiusSettingsRepository settings;
    private final SecretResolver secrets;
    private final ExternalUserProvisioner provisioner;
    private final PermissionResolver permissions;

    public RadiusAuthenticationProvider(RadiusSettingsRepository settings,
                                        SecretResolver secrets,
                                        ExternalUserProvisioner provisioner,
                                        PermissionResolver permissions) {
        this.settings = settings;
        this.secrets = secrets;
        this.provisioner = provisioner;
        this.permissions = permissions;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        RadiusSettings config = settings.current();
        // Not configured is not an error: it is the default state, and the local
        // provider ahead of this one has already had its say. Returning null
        // means "not my business", which is exactly right.
        if (!config.isEnabled() || config.getHost() == null || config.getHost().isBlank()) {
            return null;
        }

        String secret = secrets.resolve(config.getSharedSecretRef());
        if (secret == null) {
            // Enabled but the environment variable is missing or empty. This is a
            // deployment fault, not a bad password, and saying so is the only way
            // anybody finds it -- the sign-in screen looks identical otherwise.
            log.error("RADIUS is enabled but '{}' resolves to nothing. No one can sign in through it.",
                    config.getSharedSecretRef());
            throw new AuthenticationServiceException("RADIUS is not configured correctly.");
        }

        String username = authentication.getName();
        String password = authentication.getCredentials() == null
                ? null : authentication.getCredentials().toString();
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            // An empty password is an Access-Request every RADIUS server rejects,
            // so this only avoids a pointless round trip.
            throw new BadCredentialsException("Bad credentials");
        }

        if (!accepted(config, secret, username, password)) {
            throw new BadCredentialsException("Bad credentials");
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

    private boolean accepted(RadiusSettings config, String secret, String username, String password) {
        RadiusClient client = new RadiusClient(config.getHost(), secret);
        try {
            client.setAuthPort(config.getPort());
            client.setRetryCount(Math.max(1, config.getRetries()));
            client.setSocketTimeout(config.getTimeoutSeconds() * 1000);

            // Built by hand rather than via authenticate(user, password), because
            // the NAS-Identifier has to go on the request and the convenience
            // overloads have no room for it. The three-argument overload takes an
            // auth protocol, not a NAS identifier -- an easy and silent mistake.
            AccessRequest request = new AccessRequest(username, password);
            request.setAuthProtocol(AccessRequest.AUTH_PAP);
            String nas = config.getNasIdentifier();
            if (nas != null && !nas.isBlank()) {
                request.addAttribute("NAS-Identifier", nas);
            }

            RadiusPacket reply = client.authenticate(request);
            return reply != null && reply.getPacketType() == RadiusPacket.ACCESS_ACCEPT;
        } catch (Exception e) {
            // Anything that is not a clean Access-Reject lands here: no route to
            // the server, a timeout, a shared secret that does not match so the
            // reply cannot be verified. None of them mean the password is wrong.
            log.error("RADIUS authentication for '{}' could not be completed against {}:{}",
                    username, config.getHost(), config.getPort(), e);
            throw new AuthenticationServiceException("The authentication server could not be reached.", e);
        } finally {
            client.close();
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
