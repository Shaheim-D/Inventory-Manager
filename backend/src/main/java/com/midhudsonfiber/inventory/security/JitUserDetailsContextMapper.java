package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;

import java.util.Collection;

/**
 * Turns a successful directory bind into the same {@link AppUserPrincipal} every
 * other part of the application works with, provisioning the local row on first
 * sight. Doing it here (rather than in an event listener) means no code path can
 * ever see a half-mapped directory principal.
 */
public class JitUserDetailsContextMapper implements UserDetailsContextMapper {

    private final DirectoryUserProvisioner provisioner;
    private final PermissionResolver permissions;
    private final AppUser.AuthProvider provider;

    public JitUserDetailsContextMapper(DirectoryUserProvisioner provisioner,
                                       PermissionResolver permissions,
                                       AppUser.AuthProvider provider) {
        this.provisioner = provisioner;
        this.permissions = permissions;
        this.provider = provider;
    }

    @Override
    public UserDetails mapUserFromContext(DirContextOperations ctx, String username,
                                          Collection<? extends GrantedAuthority> authorities) {
        String externalId = ctx.getDn() != null ? ctx.getDn().toString() : null;
        String email = ctx.getStringAttribute("mail");
        AppUser user = provisioner.provision(username, provider, externalId, email);
        return new AppUserPrincipal(user, permissions.resolve(user));
    }

    /** Inventory Manager is never a source of truth for the directory. */
    @Override
    public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
        throw new UnsupportedOperationException("Inventory Manager never writes back to the directory");
    }
}
