package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.RadiusRoleMapping;
import com.midhudsonfiber.inventory.domain.RadiusServer;
import com.midhudsonfiber.inventory.domain.RadiusSettings;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.repo.RadiusRoleMappingRepository;
import com.midhudsonfiber.inventory.repo.RadiusServerRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import com.midhudsonfiber.inventory.repo.RadiusSettingsRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.security.RadiusClientRunner;
import com.midhudsonfiber.inventory.security.SecretCipher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings &gt; RADIUS. Gated on {@code user:manage} -- the same key as creating
 * accounts and assigning roles, because deciding who may sign in and how is the
 * same job.
 *
 * <p><b>A shared secret goes in and never comes out.</b> The response says only
 * whether one is set and whether this instance can still read it. Submitting a
 * server without a secret leaves the stored one alone, which is what makes it
 * possible to change a port without retyping a credential -- and means the
 * screen never has to hold the plaintext in order to save the rest of the form.
 */
@RestController
@RequestMapping("/api/admin/radius-settings")
public class RadiusSettingsController {

    private final RadiusSettingsRepository settings;
    private final RadiusServerRepository servers;
    private final RadiusRoleMappingRepository mappings;
    private final RoleRepository roles;
    private final SecretCipher cipher;
    private final RadiusClientRunner radius;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public RadiusSettingsController(RadiusSettingsRepository settings, RadiusServerRepository servers,
                                    RadiusRoleMappingRepository mappings, RoleRepository roles,
                                    SecretCipher cipher, RadiusClientRunner radius,
                                    AuditService audit, CurrentUser currentUser) {
        this.settings = settings;
        this.servers = servers;
        this.mappings = mappings;
        this.roles = roles;
        this.cipher = cipher;
        this.radius = radius;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** A blank or absent sharedSecret means "keep whatever is stored". */
    public record ServerRequest(String host, Integer port, String sharedSecret) {}

    public record SettingsRequest(boolean enabled, Integer timeoutSeconds, Integer retries,
                                  String nasIdentifier, String roleAttribute,
                                  List<ServerRequest> servers) {}

    public record MappingRequest(String attributeValue, Long roleId) {}

    public record TestRequest(String username, String password) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> get() {
        return view(settings.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> save(@RequestBody SettingsRequest request) {
        List<ServerRequest> submitted = request.servers() == null ? List.of() : request.servers();
        List<RadiusServer> existing = servers.findAllByOrderByOrdinalAsc();

        // Rows with nothing in them are how an empty "secondary server" section
        // comes back from a form nobody filled in. Dropping them here is what
        // lets the screen always render two slots without inventing a server.
        List<ServerRequest> wanted = submitted.stream()
                .filter(s -> s.host() != null && !s.host().isBlank())
                .toList();

        if (request.enabled() && wanted.isEmpty()) {
            throw new ApiExceptions.BadRequestException(
                    "Add at least one server before switching RADIUS sign-in on.");
        }

        List<RadiusServer> saved = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            ServerRequest submission = wanted.get(i);
            int ordinal = i + 1;

            // Matched by position, not by id, because the screen is two fixed
            // slots rather than a list somebody reorders. Ordinal 1 is the
            // primary; whatever is in the first slot is what that means.
            RadiusServer server = i < existing.size() ? existing.get(i) : new RadiusServer();
            server.setOrdinal(ordinal);
            server.setHost(submission.host().trim());
            server.setPort(submission.port() == null ? 1812 : submission.port());

            if (submission.sharedSecret() != null && !submission.sharedSecret().isBlank()) {
                server.setSharedSecretEnc(cipher.encrypt(submission.sharedSecret()));
            } else if (server.getId() == null) {
                throw new ApiExceptions.BadRequestException(
                        "A shared secret is needed for " + server.getHost() + ".");
            }
            // else: left alone, which is what an untouched masked field means.

            saved.add(servers.save(server));
        }

        // Anything beyond what was submitted has been removed from the form.
        for (int i = wanted.size(); i < existing.size(); i++) {
            servers.delete(existing.get(i));
        }

        if (request.enabled() && saved.stream().allMatch(s -> s.getSharedSecretEnc() == null)) {
            throw new ApiExceptions.BadRequestException(
                    "No server has a shared secret set, so nothing could sign in.");
        }

        RadiusSettings current = settings.current();
        current.setEnabled(request.enabled());
        current.setTimeoutSeconds(request.timeoutSeconds() == null ? 5 : request.timeoutSeconds());
        current.setRetries(request.retries() == null ? 1 : request.retries());
        current.setNasIdentifier(trimmedOrNull(request.nasIdentifier()));
        if (request.roleAttribute() != null) {
            if (!List.of("FILTER_ID", "CLASS").contains(request.roleAttribute())) {
                throw new ApiExceptions.BadRequestException(
                        "The role attribute must be FILTER_ID or CLASS.");
            }
            current.setRoleAttribute(request.roleAttribute());
        }
        current.setUpdatedBy(currentUser.principal().map(p -> p.getId()).orElse(null));
        current.setUpdatedAt(Instant.now());
        RadiusSettings persisted = settings.save(current);

        // The fact of the change and the servers by name. Never a secret, and
        // never anything from which one could be reconstructed.
        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, 1L, List.of(
                AuditService.FieldChange.of("radius_settings", null,
                        persisted.isEnabled()
                                ? "RADIUS sign-in enabled against "
                                    + saved.stream().map(RadiusServer::label).reduce((a, b) -> a + ", " + b).orElse("nothing")
                                : "RADIUS sign-in disabled")));

        return view(persisted);
    }

    /**
     * A real Access-Request with a real credential, through the same code sign-in
     * uses. Nothing less proves the path: a server can be reachable and still
     * reject everyone because the shared secret is wrong, its network policy
     * excludes this NAS, or it will not do PAP -- all of which look like a wrong
     * password later, to somebody who cannot see this screen.
     *
     * <p>The credential is used and discarded. Never stored, never logged, never
     * returned.
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> test(@RequestBody TestRequest request) {
        if (servers.count() == 0) {
            return Map.of("ok", false, "message", "Add a server first.");
        }
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isEmpty()) {
            return Map.of("ok", false, "message",
                    "Enter a username and password to test with. They are used once and not stored.");
        }

        RadiusClientRunner.Attempt attempt =
                radius.authenticate(settings.current(), request.username(), request.password());

        String where = attempt.serverLabel() == null ? "" : " (" + attempt.serverLabel() + ")";
        return switch (attempt.outcome()) {
            case ACCEPTED -> Map.of("ok", true, "message",
                    "Accepted by " + attempt.serverLabel() + ". RADIUS sign-in is working.");
            // A reject still proves the connection and the shared secret work,
            // which is worth separating from never having got there.
            case REJECTED -> Map.of("ok", false, "message",
                    "The server replied and rejected those credentials" + where
                            + ". The connection and shared secret are working; the username or "
                            + "password is what it did not like.");
            case UNREACHABLE -> Map.of("ok", false, "message",
                    attempt.detail() + " A timeout usually means a firewall, the wrong port, or a "
                            + "shared secret that does not match.");
            case NOT_CONFIGURED -> Map.of("ok", false, "message", attempt.detail());
        };
    }

    // ------------------------------------------------------------------
    // Reply attribute value -> role
    // ------------------------------------------------------------------

    @PostMapping("/role-mappings")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> addMapping(@RequestBody MappingRequest request) {
        if (request.attributeValue() == null || request.attributeValue().isBlank()) {
            throw new ApiExceptions.BadRequestException("A mapping needs the value NPS sends.");
        }
        Role role = roles.findById(request.roleId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such role"));

        String value = request.attributeValue().trim();
        // Checked here as well as by the unique index, so the answer is a
        // sentence rather than a constraint violation. Case-insensitive, because
        // that is how the match itself works -- a second mapping differing only
        // in case would be one that can never fire.
        boolean taken = mappings.findAll().stream()
                .anyMatch(m -> m.getAttributeValue().equalsIgnoreCase(value));
        if (taken) {
            throw new ApiExceptions.ConflictException(
                    "'" + value + "' is already mapped. Matching ignores case, so it can only map to one role.");
        }

        RadiusRoleMapping mapping = new RadiusRoleMapping();
        mapping.setAttributeValue(value);
        mapping.setRoleId(role.getId());
        RadiusRoleMapping saved = mappings.save(mapping);

        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, 1L, List.of(
                AuditService.FieldChange.of("radius_role_mapping", null,
                        "'" + value + "' now grants " + role.getName())));
        return Map.of("id", saved.getId());
    }

    @DeleteMapping("/role-mappings/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> removeMapping(@PathVariable Long id) {
        mappings.findById(id).ifPresent(mapping -> {
            mappings.delete(mapping);
            audit.recordFieldChanges(AuditService.ENTITY_BRANDING, 1L, List.of(
                    AuditService.FieldChange.of("radius_role_mapping",
                            "'" + mapping.getAttributeValue() + "' granted a role", null)));
        });
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> view(RadiusSettings s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", s.isEnabled());
        view.put("timeoutSeconds", s.getTimeoutSeconds());
        view.put("retries", s.getRetries());
        view.put("nasIdentifier", s.getNasIdentifier());
        view.put("roleAttribute", s.getRoleAttribute());
        view.put("updatedAt", s.getUpdatedAt());

        List<Map<String, Object>> serverViews = new ArrayList<>();
        for (RadiusServer server : servers.findAllByOrderByOrdinalAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", server.getId());
            row.put("ordinal", server.getOrdinal());
            row.put("host", server.getHost());
            row.put("port", server.getPort());
            // Two booleans instead of the secret. "Set" and "readable" are
            // different states: after a restore onto a host without the original
            // encryption key, a secret is still stored and no longer usable, and
            // the screen has to be able to say so.
            row.put("secretSet", server.getSharedSecretEnc() != null);
            row.put("secretReadable", cipher.canDecrypt(server.getSharedSecretEnc()));
            serverViews.add(row);
        }
        view.put("servers", serverViews);

        Map<Long, String> roleNames = roles.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Role::getId, Role::getName));
        List<Map<String, Object>> mappingViews = new ArrayList<>();
        for (RadiusRoleMapping mapping : mappings.findAllByOrderByAttributeValueAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", mapping.getId());
            row.put("attributeValue", mapping.getAttributeValue());
            row.put("roleId", mapping.getRoleId());
            row.put("roleName", roleNames.get(mapping.getRoleId()));
            mappingViews.add(row);
        }
        view.put("roleMappings", mappingViews);
        return view;
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
