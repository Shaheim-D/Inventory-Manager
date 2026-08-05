package com.midhudsonfiber.inventory.plugin.impl;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.LdapGroupRoleMapping;
import com.midhudsonfiber.inventory.domain.Plugin;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.plugin.*;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.LdapGroupRoleMappingRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directory group membership → roles (Phase 8 §1).
 *
 * <p>This is not authentication, and the distinction is the easiest thing in the
 * whole platform to get wrong. Logging in is Phase 6: synchronous, on every
 * attempt, and it must fail loudly. This is a background refresh of what an
 * account is <em>allowed to do</em>, so that a group change in the directory
 * eventually reaches Inventory Manager without anybody signing out and in again.
 *
 * <p>It therefore touches exactly three things: {@code user_role} rows, through
 * the normal audited path, and nothing else. It never reads or writes
 * {@code password_hash}, never clears {@code locked_until}, never resets
 * {@code failed_login_attempts}, and never creates or deactivates an account. If
 * this plugin fails, or is switched off, or is pointed at a dead server,
 * everybody signs in exactly as before with the roles they already had. There is
 * a test that asserts precisely that, because a design note is not a guarantee.
 */
@Component
public class DirectorySyncPlugin implements SyncPlugin {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final LdapGroupRoleMappingRepository mappings;
    private final AuditService audit;
    private final DirectoryReader directory;

    public DirectorySyncPlugin(AppUserRepository users, RoleRepository roles,
                               LdapGroupRoleMappingRepository mappings, AuditService audit,
                               DirectoryReader directory) {
        this.users = users;
        this.roles = roles;
        this.mappings = mappings;
        this.audit = audit;
        this.directory = directory;
    }

    @Override
    public Plugin.PluginType type() {
        return Plugin.PluginType.LDAP;
    }

    @Override
    public String displayName() {
        return "Directory group sync (LDAP / Active Directory)";
    }

    @Override
    public String description() {
        return "Keeps role assignment in step with directory group membership. "
                + "Does not authenticate anyone and never touches passwords or lockouts.";
    }

    @Override
    public boolean touchesAssets() {
        // No assets, so no confirmation gate: §7 is about proposing writes to
        // asset rows, and this plugin has no opinion about hardware at all.
        return false;
    }

    @Override
    public List<ConfigField> configurationSchema() {
        return List.of(
                ConfigField.text("url", "Directory URL", true,
                        "e.g. ldaps://dc01.example.local:636"),
                ConfigField.text("bind_dn", "Bind DN", true,
                        "The read-only account used to search. It needs no write access anywhere."),
                ConfigField.secret("bind_password_ref", "Bind password variable",
                        "The name of the environment variable holding the bind password."),
                ConfigField.text("user_search_base", "User search base", true,
                        "e.g. OU=Staff,DC=example,DC=local"),
                ConfigField.text("username_attribute", "Username attribute", false,
                        "Defaults to sAMAccountName, which is what Active Directory uses."),
                ConfigField.text("group_attribute", "Group membership attribute", false,
                        "Defaults to memberOf."),
                ConfigField.number("sync_interval_minutes", "Sync every (minutes)", false,
                        "Leave blank for the suggested 60 minutes."),
                ConfigField.flag("remove_roles_not_in_directory",
                        "Take away roles the directory no longer grants",
                        "Off by default. On, this is how a leaver loses access; off, it only "
                                + "ever adds — which is safer to switch on first and watch."));
    }

    @Override
    public int defaultSyncIntervalMinutes() {
        return 60;
    }

    @Override
    public ConnectionTest testConnection(PluginConfig config) {
        try {
            int found = directory.countUsers(settings(config));
            return ConnectionTest.ok("Connected. " + found + " account(s) visible under the search base.");
        } catch (RuntimeException e) {
            return ConnectionTest.failed(e.getMessage());
        }
    }

    @Override
    @Transactional
    public SyncOutcome collect(PluginConfig config) {
        Map<String, Set<String>> groupsByUser = directory.groupsByUsername(settings(config));

        Map<String, Set<Long>> rolesByGroup = new LinkedHashMap<>();
        for (LdapGroupRoleMapping mapping : mappings.findAll()) {
            rolesByGroup.computeIfAbsent(mapping.getGroupIdentifier().toLowerCase(), key -> new HashSet<>())
                    .add(mapping.getRoleId());
        }

        boolean removeMissing = config.flag("remove_roles_not_in_directory");
        int updated = 0;
        int failed = 0;
        List<String> notes = new ArrayList<>();

        for (AppUser user : users.findAll()) {
            Set<String> groups = groupsByUser.get(user.getUsername().toLowerCase());
            if (groups == null) continue;

            Set<Long> granted = new HashSet<>();
            for (String group : groups) {
                granted.addAll(rolesByGroup.getOrDefault(group.toLowerCase(), Set.of()));
            }

            Set<Role> before = new HashSet<>(user.getRoles());
            Set<Role> after = new HashSet<>(before);
            roles.findAllById(granted).forEach(after::add);
            if (removeMissing) {
                Set<Long> mappedRoleIds = rolesByGroup.values().stream()
                        .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
                // Only roles the directory is responsible for are taken away.
                // A role granted by hand in Inventory Manager is not the
                // directory's to remove.
                after.removeIf(role -> mappedRoleIds.contains(role.getId()) && !granted.contains(role.getId()));
            }

            if (before.equals(after)) continue;
            try {
                user.setRoles(after);
                users.save(user);
                audit.recordFieldChanges(AuditService.ENTITY_APP_USER, user.getId(), List.of(
                        AuditService.FieldChange.of("roles", names(before), names(after))));
                updated++;
                notes.add(user.getUsername());
            } catch (RuntimeException e) {
                failed++;
            }
        }

        String message = updated == 0
                ? "Every account already matched the directory."
                : "Role assignment updated for " + String.join(", ", notes) + ".";
        return SyncOutcome.reporting(new Counters(0, updated, failed), message);
    }

    private static String names(Set<Role> roles) {
        return roles.stream().map(Role::getName).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private DirectoryReader.Settings settings(PluginConfig config) {
        return new DirectoryReader.Settings(
                config.text("url"),
                config.text("bind_dn"),
                config.secret("bind_password_ref"),
                config.text("user_search_base"),
                config.text("username_attribute") == null ? "sAMAccountName" : config.text("username_attribute"),
                config.text("group_attribute") == null ? "memberOf" : config.text("group_attribute"));
    }

    /**
     * The directory itself, behind a seam.
     *
     * <p>Separated so the plugin's logic — which roles a person should end up
     * with, and what it must never touch — can be tested without an LDAP server
     * in the loop. The boundary this class exists to protect is worth being able
     * to test cheaply and often.
     */
    public interface DirectoryReader {

        record Settings(String url, String bindDn, String bindPassword, String searchBase,
                        String usernameAttribute, String groupAttribute) {}

        Map<String, Set<String>> groupsByUsername(Settings settings);

        int countUsers(Settings settings);
    }

    /** The real one: a read-only JNDI search, no writes of any kind. */
    @Component
    public static class JndiDirectoryReader implements DirectoryReader {

        @Override
        public Map<String, Set<String>> groupsByUsername(Settings settings) {
            Map<String, Set<String>> found = new LinkedHashMap<>();
            search(settings, result -> {
                Attribute username = result.getAttributes().get(settings.usernameAttribute());
                Attribute groups = result.getAttributes().get(settings.groupAttribute());
                if (username == null) return;
                Set<String> memberships = new HashSet<>();
                if (groups != null) {
                    for (int i = 0; i < groups.size(); i++) {
                        memberships.add(String.valueOf(groups.get(i)));
                    }
                }
                found.put(String.valueOf(username.get()).toLowerCase(), memberships);
            });
            return found;
        }

        @Override
        public int countUsers(Settings settings) {
            int[] count = {0};
            search(settings, result -> count[0]++);
            return count[0];
        }

        private void search(Settings settings, ResultHandler handler) {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            environment.put(Context.PROVIDER_URL, require(settings.url(), "directory URL"));
            environment.put(Context.SECURITY_AUTHENTICATION, "simple");
            environment.put(Context.SECURITY_PRINCIPAL, require(settings.bindDn(), "bind DN"));
            environment.put(Context.SECURITY_CREDENTIALS, require(settings.bindPassword(), "bind password"));
            environment.put("com.sun.jndi.ldap.connect.timeout", "10000");
            environment.put("com.sun.jndi.ldap.read.timeout", "30000");

            DirContext context = null;
            try {
                context = new InitialDirContext(environment);
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(
                        new String[]{settings.usernameAttribute(), settings.groupAttribute()});

                NamingEnumeration<SearchResult> results = context.search(
                        require(settings.searchBase(), "search base"), "(objectClass=person)", controls);
                while (results.hasMore()) {
                    handler.accept(results.next());
                }
            } catch (Exception e) {
                throw new PluginException("Directory search failed: " + e.getMessage(), e);
            } finally {
                if (context != null) {
                    try {
                        context.close();
                    } catch (Exception ignored) {
                        // Closing a context that is already gone is not news.
                    }
                }
            }
        }

        private static String require(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new PluginException("This plugin has no " + what + " configured.");
            }
            return value;
        }

        @FunctionalInterface
        private interface ResultHandler {
            void accept(SearchResult result) throws Exception;
        }
    }
}
