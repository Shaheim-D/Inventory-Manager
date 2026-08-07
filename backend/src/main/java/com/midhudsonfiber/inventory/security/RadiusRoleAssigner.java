package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.RadiusRoleMapping;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.RadiusRoleMappingRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the role attribute on an Access-Accept into this application's roles.
 *
 * <p>Three rules, and each one is load-bearing.
 *
 * <p><b>Only accounts whose {@code auth_provider} is RADIUS.</b> An account
 * created in this application keeps the roles somebody gave it here, even if
 * that person also signs in through RADIUS. Without this line an administrator
 * whose NPS profile carries no matching attribute would be demoted to Unassigned
 * by their own sign-in -- and the local password that should have rescued them
 * would then belong to an account with no permissions. The bootstrap admin is a
 * local account, so it can never be affected by anything NPS does or fails to
 * do.
 *
 * <p><b>Authoritative for the accounts it does apply to.</b> Roles are replaced,
 * not added to, so removing somebody from a group in NPS removes their access
 * here at their next sign-in rather than never. Directory-driven access that
 * only ever grants is not access control, it is an accumulation.
 *
 * <p><b>No recognised value means Unassigned.</b> Not "no roles" and not "keep
 * what they had": somebody whose reply carries nothing this application knows
 * about is a real employee who has just signed in, so they land on a read-only
 * view of assets and the dashboard rather than a screen that refuses everything.
 *
 * <p>Every change is audited, because a role arriving from somewhere else is
 * exactly the kind of thing somebody will later need to explain.
 */
@Service
public class RadiusRoleAssigner {

    private static final Logger log = LoggerFactory.getLogger(RadiusRoleAssigner.class);
    private static final String FALLBACK_ROLE = "Unassigned";

    private final RadiusRoleMappingRepository mappings;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final AuditService audit;

    public RadiusRoleAssigner(RadiusRoleMappingRepository mappings, RoleRepository roles,
                              AppUserRepository users, AuditService audit) {
        this.mappings = mappings;
        this.roles = roles;
        this.users = users;
        this.audit = audit;
    }

    /**
     * @param user            the account that just authenticated
     * @param attributeValues what the reply carried, possibly empty
     * @return the same account, with roles reflecting the reply
     */
    @Transactional
    public AppUser apply(AppUser user, List<String> attributeValues) {
        if (user.getAuthProvider() != AppUser.AuthProvider.RADIUS) {
            // Managed here, not there. See the class note -- this is the line
            // that keeps a local administrator safe from their own NPS profile.
            return user;
        }

        Set<Role> resolved = rolesFor(attributeValues);
        if (resolved.isEmpty()) {
            roles.findByName(FALLBACK_ROLE).ifPresent(resolved::add);
        }

        Set<String> before = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        Set<String> after = resolved.stream().map(Role::getName).collect(Collectors.toSet());
        if (before.equals(after)) {
            return user;   // the usual case: nothing to write on every sign-in
        }

        user.setRoles(resolved);
        AppUser saved = users.save(user);

        log.info("RADIUS reply changed roles for '{}': {} -> {}", user.getUsername(), before, after);
        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, user.getId(), List.of(
                AuditService.FieldChange.of("roles",
                        String.join(", ", before.stream().sorted().toList()),
                        String.join(", ", after.stream().sorted().toList()))));
        return saved;
    }

    private Set<Role> rolesFor(List<String> attributeValues) {
        if (attributeValues == null || attributeValues.isEmpty()) return new LinkedHashSet<>();

        // Built once per sign-in rather than queried per value: there are four
        // of these, and a person in three groups should not be three queries.
        Map<String, Long> byValue = mappings.findAll().stream()
                .collect(Collectors.toMap(
                        m -> m.getAttributeValue().toLowerCase(Locale.ROOT),
                        RadiusRoleMapping::getRoleId,
                        (first, second) -> first));

        Set<Role> resolved = new LinkedHashSet<>();
        for (String value : attributeValues) {
            Long roleId = byValue.get(value.trim().toLowerCase(Locale.ROOT));
            if (roleId == null) {
                // Worth a line: an NPS policy sending something nobody mapped is
                // silent otherwise, and looks to the person like "my access
                // vanished" rather than "one string does not match".
                log.info("RADIUS reply carried '{}', which no role mapping matches.", value);
                continue;
            }
            roles.findById(roleId).ifPresent(resolved::add);
        }
        return resolved;
    }

    /** Exposed for the settings screen, which shows what a value would grant. */
    public Function<String, String> roleNameLookup() {
        Map<Long, String> names = roles.findAll().stream()
                .collect(Collectors.toMap(Role::getId, Role::getName));
        return id -> names.get(Long.valueOf(id));
    }
}
