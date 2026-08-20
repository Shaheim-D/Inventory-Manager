package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.LdapRoleMapping;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.LdapRoleMappingRepository;
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
import java.util.stream.Collectors;

/**
 * Turns directory group membership into this application's roles.
 *
 * <p>The same three rules {@link RadiusRoleAssigner} follows, deliberately, so
 * the two halves of Settings &gt; Remote Authentication behave identically:
 *
 * <p><b>Only accounts whose {@code auth_provider} is LDAP.</b> An account
 * created here keeps the roles a person gave it. Without this line an
 * administrator who happens to exist in the directory but is in no mapped group
 * would be demoted to Unassigned by their own sign-in — and the local password
 * that should have rescued them would then belong to an account with no
 * permissions. The bootstrap admin is a local account and is untouchable by
 * anything the directory says or fails to say.
 *
 * <p><b>Authoritative for the accounts it does apply to.</b> Roles are
 * replaced, not added to, so removing somebody from a group in AD removes their
 * access here at their next sign-in rather than never. Directory-driven access
 * that only ever grants is not access control, it is an accumulation.
 *
 * <p><b>No recognised group means Unassigned.</b> Somebody in no mapped group
 * is a real employee who has just signed in, so they land on a read-only view
 * rather than a screen that refuses everything.
 *
 * <p>What differs from RADIUS is only the matching. {@code memberOf} carries a
 * full DN, so a mapping matches either the whole DN or just its CN — requiring
 * an operator to transcribe {@code CN=IT Staff,OU=Groups,DC=corp,DC=example,DC=com}
 * exactly would make granting a role an exercise in copying punctuation.
 */
@Service
public class LdapRoleAssigner {

    private static final Logger log = LoggerFactory.getLogger(LdapRoleAssigner.class);
    private static final String FALLBACK_ROLE = "Unassigned";

    private final LdapRoleMappingRepository mappings;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final AuditService audit;

    public LdapRoleAssigner(LdapRoleMappingRepository mappings, RoleRepository roles,
                            AppUserRepository users, AuditService audit) {
        this.mappings = mappings;
        this.roles = roles;
        this.users = users;
        this.audit = audit;
    }

    /**
     * @param groups what {@code memberOf} carried, possibly empty
     * @return the same account, with roles reflecting the directory
     */
    @Transactional
    public AppUser apply(AppUser user, List<String> groups) {
        if (user.getAuthProvider() != AppUser.AuthProvider.LDAP) {
            // Managed here, not there. This is the line that keeps a local
            // administrator safe from their own directory entry.
            return user;
        }

        Set<Role> resolved = rolesFor(groups);
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

        log.info("Directory groups changed roles for '{}': {} -> {}", user.getUsername(), before, after);
        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, user.getId(), List.of(
                AuditService.FieldChange.of("roles",
                        String.join(", ", before.stream().sorted().toList()),
                        String.join(", ", after.stream().sorted().toList()))));
        return saved;
    }

    private Set<Role> rolesFor(List<String> groups) {
        if (groups == null || groups.isEmpty()) return new LinkedHashSet<>();

        // Built once per sign-in rather than queried per group: somebody in
        // fifteen AD groups should not be fifteen queries.
        Map<String, Long> byValue = mappings.findAll().stream()
                .collect(Collectors.toMap(
                        m -> m.getGroupValue().trim().toLowerCase(Locale.ROOT),
                        LdapRoleMapping::getRoleId,
                        (first, second) -> first));

        Set<Role> resolved = new LinkedHashSet<>();
        for (String group : groups) {
            if (group == null || group.isBlank()) continue;
            String full = group.trim().toLowerCase(Locale.ROOT);

            // The whole DN first, then its CN. Both are legitimate ways to name
            // the same group, and an operator should be able to write either.
            Long roleId = byValue.get(full);
            if (roleId == null) roleId = byValue.get(commonNameOf(full));

            if (roleId == null) {
                // Worth a line: a group nobody mapped is silent otherwise, and
                // looks to the person like "my access vanished" rather than
                // "one string does not match".
                log.debug("Directory group '{}' matches no role mapping", group);
                continue;
            }
            roles.findById(roleId).ifPresent(resolved::add);
        }
        return resolved;
    }

    /**
     * {@code cn=it staff,ou=groups,dc=corp} to {@code it staff}.
     *
     * <p>Returns the input unchanged when it is not a DN, so a directory that
     * publishes bare group names works without configuration.
     */
    public static String commonNameOf(String value) {
        if (!value.startsWith("cn=")) return value;
        int comma = value.indexOf(',');
        String cn = comma < 0 ? value.substring(3) : value.substring(3, comma);
        return cn.trim();
    }
}
