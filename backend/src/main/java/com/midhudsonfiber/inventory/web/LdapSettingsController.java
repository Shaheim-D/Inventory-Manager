package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.LdapRoleMapping;
import com.midhudsonfiber.inventory.domain.LdapSettings;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.repo.LdapRoleMappingRepository;
import com.midhudsonfiber.inventory.repo.LdapSettingsRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.LdapClientRunner;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.security.SecretCipher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LDAP / Active Directory sign-in, configured from Settings.
 *
 * <p>Gated on {@code user:manage}, the same key RADIUS uses: deciding how
 * people sign in and which directory group grants which role is the same job as
 * administering accounts, and a separate permission for it would be a
 * distinction nobody administering this would recognise.
 *
 * <p>The service account password goes in and never comes out. The response
 * says whether one is set, never what it is, and an update that omits it keeps
 * the stored one — so saving after changing the host does not silently blank
 * the credential.
 */
@RestController
@RequestMapping("/api/admin/ldap-settings")
public class LdapSettingsController {

    private final LdapSettingsRepository settings;
    private final LdapRoleMappingRepository mappings;
    private final RoleRepository roles;
    private final LdapClientRunner ldap;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public LdapSettingsController(LdapSettingsRepository settings, LdapRoleMappingRepository mappings,
                                  RoleRepository roles, LdapClientRunner ldap, SecretCipher cipher,
                                  AuditService audit, CurrentUser currentUser) {
        this.settings = settings;
        this.mappings = mappings;
        this.roles = roles;
        this.ldap = ldap;
        this.cipher = cipher;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record SettingsRequest(boolean enabled, String host, Integer port, String transport,
                                  String userSearchBase, String userSearchFilter,
                                  String groupAttribute, String upnSuffix,
                                  String bindDn, String bindPassword,
                                  Integer connectTimeoutSeconds) {}

    public record TestRequest(String username, String password) {}

    public record MappingRequest(String groupValue, Long roleId) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> get() {
        return toView(current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> update(@RequestBody SettingsRequest request) {
        LdapSettings config = current();

        config.setHost(blankToNull(request.host()));
        config.setPort(request.port() == null ? 636 : request.port());
        config.setTransport(parseTransport(request.transport()));
        config.setUserSearchBase(blankToNull(request.userSearchBase()));
        if (blankToNull(request.userSearchFilter()) != null) {
            config.setUserSearchFilter(request.userSearchFilter().trim());
        }
        if (blankToNull(request.groupAttribute()) != null) {
            config.setGroupAttribute(request.groupAttribute().trim());
        }
        config.setUpnSuffix(blankToNull(request.upnSuffix()));
        config.setBindDn(blankToNull(request.bindDn()));
        if (request.connectTimeoutSeconds() != null) {
            config.setConnectTimeoutSeconds(clamp(request.connectTimeoutSeconds(), 1, 60));
        }

        // Absent means unchanged; an explicit empty string means clear it.
        if (request.bindPassword() != null) {
            config.setBindPasswordEnc(request.bindPassword().isEmpty()
                    ? null : cipher.encrypt(request.bindPassword()));
        }

        config.setEnabled(request.enabled());
        if (request.enabled()) {
            // Mirrors the CHECK constraint so this arrives as a sentence rather
            // than a constraint violation.
            if (config.getHost() == null || config.getUserSearchBase() == null) {
                throw new ApiExceptions.BadRequestException(
                        "A host and a user search base are needed before LDAP sign-in can be turned on.");
            }
            if (config.getUpnSuffix() == null && config.getBindDn() == null) {
                throw new ApiExceptions.BadRequestException(
                        "Give either a UPN suffix (bind as the person) or a service account DN "
                        + "(look the person up first). Without one there is no way to reach the directory.");
            }
        }

        LdapSettings saved = settings.save(config);
        // The password is never in the audit trail, only the fact of a change.
        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, 0L, List.of(
                AuditService.FieldChange.of("ldap_settings", null,
                        saved.isEnabled()
                                ? "LDAP sign-in enabled against " + saved.getHost()
                                : "LDAP sign-in disabled")));
        return toView(saved);
    }

    /**
     * Proves the settings work before anybody depends on them.
     *
     * <p>Runs the real sign-in path — same {@link LdapClientRunner} the provider
     * uses — against a username and password the administrator supplies, and
     * reports the groups it found. Those groups are the useful half: they are
     * the exact strings a role mapping has to match, so this is also how an
     * operator discovers what to type.
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> test(@RequestBody TestRequest request) {
        LdapSettings config = current();
        if (config.getHost() == null || config.getUserSearchBase() == null) {
            throw new ApiExceptions.BadRequestException(
                    "Save a host and a user search base before testing.");
        }
        if (blankToNull(request.username()) == null || request.password() == null
                || request.password().isEmpty()) {
            throw new ApiExceptions.BadRequestException(
                    "Give a directory username and password to test with.");
        }

        String bindPassword = null;
        if (config.getBindDn() != null && config.getBindPasswordEnc() != null
                && cipher.canDecrypt(config.getBindPasswordEnc())) {
            bindPassword = cipher.decrypt(config.getBindPasswordEnc());
        }

        try {
            // Tested against the settings as saved, including enabled=false --
            // the point is to get it working before switching it on.
            LdapClientRunner.DirectoryUser found =
                    ldap.authenticate(config, bindPassword, request.username().trim(), request.password());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("message", "Signed in as " + found.distinguishedName() + ".");
            result.put("distinguishedName", found.distinguishedName());
            result.put("email", found.email());
            result.put("groups", found.groups());
            result.put("mappedRoles", rolesForPreview(found.groups()));
            return result;
        } catch (LdapClientRunner.LdapFailure failure) {
            // Reported verbatim, because this is the one place somebody is
            // explicitly asking what went wrong.
            return Map.of("ok", false, "message", failure.getMessage(),
                    "kind", failure.kind().name());
        }
    }

    // ---- Group to role mappings --------------------------------------

    @GetMapping("/role-mappings")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public List<Map<String, Object>> mappings() {
        return mappings.findAllByOrderByGroupValueAsc().stream().map(mapping -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", mapping.getId());
            view.put("groupValue", mapping.getGroupValue());
            view.put("roleId", mapping.getRoleId());
            view.put("roleName", roles.findById(mapping.getRoleId()).map(Role::getName).orElse(null));
            return view;
        }).toList();
    }

    @PostMapping("/role-mappings")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> addMapping(@RequestBody MappingRequest request) {
        String value = blankToNull(request.groupValue());
        if (value == null) {
            throw new ApiExceptions.BadRequestException("Give the directory group to map.");
        }
        if (request.roleId() == null || roles.findById(request.roleId()).isEmpty()) {
            throw new ApiExceptions.BadRequestException("Choose a role for this group.");
        }

        LdapRoleMapping mapping = new LdapRoleMapping();
        mapping.setGroupValue(value);
        mapping.setRoleId(request.roleId());
        LdapRoleMapping saved = mappings.save(mapping);

        audit.recordCreate(AuditService.ENTITY_ROLE, request.roleId(),
                "LDAP group '" + value + "' now grants this role");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", saved.getId());
        view.put("groupValue", saved.getGroupValue());
        view.put("roleId", saved.getRoleId());
        view.put("roleName", roles.findById(saved.getRoleId()).map(Role::getName).orElse(null));
        return view;
    }

    @DeleteMapping("/role-mappings/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> removeMapping(@PathVariable Long id) {
        mappings.findById(id).ifPresent(mapping -> {
            mappings.delete(mapping);
            audit.recordDelete(AuditService.ENTITY_ROLE, mapping.getRoleId(),
                    "LDAP group '" + mapping.getGroupValue() + "' no longer grants this role");
        });
        return ResponseEntity.noContent().build();
    }

    // ---- helpers -----------------------------------------------------

    private LdapSettings current() {
        return settings.findById((short) 1).orElseGet(LdapSettings::new);
    }

    /** Which roles the test user would land in, so the preview is complete. */
    private List<String> rolesForPreview(List<String> groups) {
        Map<String, Long> byValue = new LinkedHashMap<>();
        for (LdapRoleMapping mapping : mappings.findAll()) {
            byValue.put(mapping.getGroupValue().trim().toLowerCase(Locale.ROOT), mapping.getRoleId());
        }
        return groups.stream()
                .map(group -> group.trim().toLowerCase(Locale.ROOT))
                .map(group -> {
                    Long roleId = byValue.get(group);
                    if (roleId == null) {
                        roleId = byValue.get(
                                com.midhudsonfiber.inventory.security.LdapRoleAssigner.commonNameOf(group));
                    }
                    return roleId;
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(roleId -> roles.findById(roleId).map(Role::getName).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Map<String, Object> toView(LdapSettings config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", config.isEnabled());
        view.put("host", config.getHost());
        view.put("port", config.getPort());
        view.put("transport", config.getTransport() == null ? null : config.getTransport().name());
        view.put("userSearchBase", config.getUserSearchBase());
        view.put("userSearchFilter", config.getUserSearchFilter());
        view.put("groupAttribute", config.getGroupAttribute());
        view.put("upnSuffix", config.getUpnSuffix());
        view.put("bindDn", config.getBindDn());
        view.put("connectTimeoutSeconds", config.getConnectTimeoutSeconds());

        // Whether there is one, never what it is.
        view.put("bindPasswordSet",
                config.getBindPasswordEnc() != null && !config.getBindPasswordEnc().isBlank());
        // A restore onto a host without APP_ENCRYPTION_KEY leaves a password
        // that cannot be read. The screen says so rather than failing at the
        // next sign-in.
        view.put("bindPasswordReadable", config.getBindPasswordEnc() == null
                || cipher.canDecrypt(config.getBindPasswordEnc()));
        view.put("usable", config.isUsable());
        view.put("updatedAt", config.getUpdatedAt());
        return view;
    }

    private static LdapSettings.Transport parseTransport(String value) {
        if (blankToNull(value) == null) return LdapSettings.Transport.LDAPS;
        try {
            return LdapSettings.Transport.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException(
                    "Unknown transport '" + value + "'. Use LDAPS, STARTTLS or NONE.");
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
