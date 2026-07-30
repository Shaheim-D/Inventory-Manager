package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.Permission;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.domain.UserPermissionOverride;
import com.midhudsonfiber.inventory.repo.UserPermissionOverrideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves a user's effective permission keys: the union of their roles'
 * permissions, plus individual GRANT overrides, minus individual DENY overrides.
 * DENY always wins. This is the only place that answer is computed.
 */
@Service
public class PermissionResolver {

    private final UserPermissionOverrideRepository overrides;

    public PermissionResolver(UserPermissionOverrideRepository overrides) {
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public Set<String> resolve(AppUser user) {
        Set<String> granted = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            for (Permission permission : role.getPermissions()) {
                granted.add(permission.getPermissionKey());
            }
        }

        Set<String> denied = new LinkedHashSet<>();
        for (UserPermissionOverride override : overrides.findByUserId(user.getId())) {
            String key = override.getPermission().getPermissionKey();
            if (override.getEffect() == UserPermissionOverride.Effect.DENY) {
                denied.add(key);
            } else {
                granted.add(key);
            }
        }
        granted.removeAll(denied);

        return new TreeSet<>(granted);
    }
}
