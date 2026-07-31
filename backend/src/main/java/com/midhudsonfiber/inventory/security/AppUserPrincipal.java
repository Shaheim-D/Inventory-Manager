package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Permission keys are the granted authorities. There is no ROLE_ authority
 * anywhere in this application by design -- nothing may ever check a role name.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final boolean active;
    private final boolean locked;
    private final boolean mustChangePassword;
    private final AppUser.AuthProvider authProvider;
    private final Set<String> permissions;
    private final List<String> roleNames;

    public AppUserPrincipal(AppUser user, Set<String> permissions) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.passwordHash = user.getPasswordHash();
        this.active = user.isActive();
        this.locked = user.isCurrentlyLocked();
        this.mustChangePassword = user.isMustChangePassword();
        this.authProvider = user.getAuthProvider();
        this.permissions = permissions;
        this.roleNames = user.getRoles().stream().map(r -> r.getName()).sorted().toList();
    }

    public Long getId() { return id; }
    public Set<String> getPermissions() { return permissions; }
    public List<String> getRoleNames() { return roleNames; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public AppUser.AuthProvider getAuthProvider() { return authProvider; }

    public boolean has(String permissionKey) { return permissions.contains(permissionKey); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return !locked; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}
