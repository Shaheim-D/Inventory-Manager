package com.midhudsonfiber.inventory.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** Convenience accessor for the authenticated principal. */
@Component
public class CurrentUser {

    public Optional<AppUserPrincipal> principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof AppUserPrincipal p) return Optional.of(p);
        return Optional.empty();
    }

    public Long idOrNull() {
        return principal().map(AppUserPrincipal::getId).orElse(null);
    }

    public Set<String> permissions() {
        return principal().map(AppUserPrincipal::getPermissions).orElse(Set.of());
    }

    public boolean has(String permissionKey) {
        return permissions().contains(permissionKey);
    }
}
