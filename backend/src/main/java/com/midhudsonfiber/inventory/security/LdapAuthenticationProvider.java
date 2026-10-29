package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.LdapSettings;
import com.midhudsonfiber.inventory.repo.LdapSettingsRepository;
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
 * Signs people in against LDAP / Active Directory.
 *
 * <p>Third in the chain, behind local accounts and RADIUS, and built to the same
 * rules as {@link RadiusAuthenticationProvider}:
 *
 * <p><b>Settings are read on every attempt</b>, not captured when the bean is
 * built — switching it on, fixing a typo in the host, or turning it off during
 * an incident takes effect now, without a restart.
 *
 * <p><b>Local accounts are tried first</b> and are unaffected by anything here,
 * so a misconfigured or unreachable directory can never lock an administrator
 * out of the local account they need to fix it.
 *
 * <p><b>An unreachable directory is not a rejection.</b> It throws
 * {@link AuthenticationServiceException} rather than BadCredentials, so the
 * sign-in screen says the server is unreachable instead of telling somebody
 * with a perfectly good password that it is wrong.
 *
 * <p>Talking to the directory is {@link LdapClientRunner}'s job, shared with the
 * settings screen's test button. Turning {@code memberOf} into roles is
 * {@link LdapRoleAssigner}'s.
 */
@Component
public class LdapAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticationProvider.class);

    private final LdapSettingsRepository settings;
    private final LdapClientRunner ldap;
    private final SecretCipher cipher;
    private final ExternalUserProvisioner provisioner;
    private final LdapRoleAssigner roleAssigner;
    private final PermissionResolver permissions;

    public LdapAuthenticationProvider(LdapSettingsRepository settings,
                                      LdapClientRunner ldap,
                                      SecretCipher cipher,
                                      ExternalUserProvisioner provisioner,
                                      LdapRoleAssigner roleAssigner,
                                      PermissionResolver permissions) {
        this.settings = settings;
        this.ldap = ldap;
        this.cipher = cipher;
        this.provisioner = provisioner;
        this.roleAssigner = roleAssigner;
        this.permissions = permissions;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        LdapSettings config = settings.findById((short) 1).orElse(null);
        // Not configured is not an error: it is the default state, and the
        // providers ahead of this one have already had their say. Returning
        // null means "not my business", which is exactly right.
        if (config == null || !config.isEnabled()) {
            return null;
        }

        String username = authentication.getName();
        String password = authentication.getCredentials() == null
                ? null : authentication.getCredentials().toString();
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            // Refused here as well as in the client. An empty credential is an
            // ANONYMOUS bind that LDAP answers with success, so this is the one
            // check that must not be left to a single layer.
            throw new BadCredentialsException("Bad credentials");
        }

        if (!config.isUsable()) {
            // Switched on with nothing usable behind it: a deployment fault, not
            // a bad password. Saying so is the only way anybody finds it,
            // because the sign-in screen looks identical otherwise.
            log.error("LDAP sign-in is enabled but not fully configured (host, search base, or bind).");
            throw new AuthenticationServiceException("LDAP is not configured correctly.");
        }

        String bindPassword = null;
        if (config.getBindDn() != null && !config.getBindDn().isBlank()) {
            String stored = config.getBindPasswordEnc();
            if (stored != null && !stored.isBlank()) {
                if (!cipher.canDecrypt(stored)) {
                    // The encryption key is deliberately not in backups, so a
                    // restore onto a new host lands here. Saying which is the
                    // difference between re-entering one password and hunting a
                    // sign-in bug.
                    log.error("The LDAP service account password cannot be decrypted on this host. "
                              + "Re-enter it in Settings > Remote Authentication.");
                    throw new AuthenticationServiceException(
                            "The LDAP service account password cannot be read on this host.");
                }
                bindPassword = cipher.decrypt(stored);
            }
        }

        LdapClientRunner.DirectoryUser found;
        try {
            found = ldap.authenticate(config, bindPassword, username, password);
        } catch (LdapClientRunner.LdapFailure failure) {
            switch (failure.kind()) {
                case REJECTED, NOT_FOUND -> throw new BadCredentialsException("Bad credentials");
                case MISCONFIGURED -> {
                    log.error("LDAP sign-in is misconfigured: {}", failure.getMessage());
                    throw new AuthenticationServiceException("LDAP is not configured correctly.");
                }
                default -> throw new AuthenticationServiceException(
                        "The directory server could not be reached.");
            }
        }

        AppUser user = provisioner.provision(
                username, AppUser.AuthProvider.LDAP, found.distinguishedName(), found.email());

        // Roles from memberOf, before permissions are resolved -- so somebody
        // moved between groups in AD gets the new access on this sign-in rather
        // than the one after it.
        user = roleAssigner.apply(user, found.groups());

        if (!user.isActive()) {
            // Disabling an account has to hold even though the password was
            // accepted upstream: the directory knows nothing about this
            // application's idea of a disabled user.
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
