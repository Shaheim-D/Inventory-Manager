package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.Permission;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.domain.UserPermissionOverride;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.security.PermissionResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserPermissionOverrideRepository overrides;
    private final PermissionResolver permissionResolver;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public UserAdminController(AppUserRepository users, RoleRepository roles,
                               PermissionRepository permissions,
                               UserPermissionOverrideRepository overrides,
                               PermissionResolver permissionResolver,
                               PasswordEncoder passwordEncoder,
                               AuditService audit,
                               CurrentUser currentUser) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.overrides = overrides;
        this.permissionResolver = permissionResolver;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record CreateUserRequest(@NotBlank String username, String email, String password,
                                    Set<Long> roleIds) {}

    public record UpdateUserRequest(String email, Boolean active, Set<Long> roleIds) {}

    public record ResetPasswordRequest(@NotBlank String newPassword) {}

    public record OverrideRequest(Long permissionId, UserPermissionOverride.Effect effect) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public List<Map<String, Object>> list() {
        return users.findAllByOrderByUsernameAsc().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> get(@PathVariable Long id) {
        Map<String, Object> view = new LinkedHashMap<>(toView(user(id)));
        view.put("overrides", overrides.findByUserId(id).stream()
                .map(o -> Map.of(
                        "id", o.getId(),
                        "permissionId", o.getPermission().getId(),
                        "permissionKey", o.getPermission().getPermissionKey(),
                        "effect", o.getEffect().name()))
                .toList());
        view.put("effectivePermissions", permissionResolver.resolve(user(id)));
        return view;
    }

    /**
     * Accounts created here are always local. Directory accounts are not created
     * by hand: an LDAP/AD user appears the first time they sign in, provisioned
     * into Unassigned, and RADIUS will work the same way. Asking an administrator
     * to pick an authentication provider offered a choice that only ever had one
     * correct answer, so the field is gone.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = new AppUser();
        user.setUsername(request.username().trim());
        user.setEmail(request.email());
        user.setAuthProvider(AppUser.AuthProvider.LOCAL);
        user.setActive(true);

        if (request.password() == null || request.password().isBlank()) {
            throw new ApiExceptions.BadRequestException("A password is required.");
        }
        AuthController.validatePasswordStrength(request.password());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // Admin-issued credentials are always temporary.
        user.setMustChangePassword(true);
        user.setRoles(resolveRoles(request.roleIds()));

        AppUser saved = users.save(user);
        audit.recordCreate(AuditService.ENTITY_APP_USER, saved.getId(), saved.getUsername());
        return toView(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        AppUser user = user(id);
        List<AuditService.FieldChange> changes = new ArrayList<>();
        changes.add(AuditService.FieldChange.of("email", user.getEmail(), request.email()));

        if (request.active() != null) {
            if (!request.active() && id.equals(currentUser.idOrNull())) {
                throw new ApiExceptions.BadRequestException("You cannot deactivate your own account.");
            }
            changes.add(AuditService.FieldChange.of("is_active", user.isActive(), request.active()));
            user.setActive(request.active());
        }
        user.setEmail(request.email());

        if (request.roleIds() != null) {
            Set<Role> updated = resolveRoles(request.roleIds());
            changes.add(AuditService.FieldChange.of("roles",
                    roleNames(user.getRoles()), roleNames(updated)));
            user.setRoles(updated);
        }

        AppUser saved = users.save(user);
        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, id, changes);
        return toView(saved);
    }

    /** Unlocks an account without waiting out the lockout window. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> unlock(@PathVariable Long id) {
        AppUser user = user(id);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        users.save(user);
        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, id,
                List.of(AuditService.FieldChange.of("locked_until", "locked", null)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        AppUser user = user(id);
        if (user.getAuthProvider() != AppUser.AuthProvider.LOCAL) {
            throw new ApiExceptions.BadRequestException(
                    "Directory account passwords are managed in the directory, not here.");
        }
        AuthController.validatePasswordStrength(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(true);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        users.save(user);
        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, id,
                List.of(AuditService.FieldChange.of("password_hash", "(previous)", "(reset)")));
        return ResponseEntity.noContent().build();
    }

    // ---------------- individual permission overrides ----------------

    @PostMapping("/{id}/overrides")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    @Transactional
    public Map<String, Object> setOverride(@PathVariable Long id, @RequestBody OverrideRequest request) {
        AppUser user = user(id);
        Permission permission = permissions.findById(request.permissionId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Permission not found"));

        UserPermissionOverride override = overrides.findByUserId(id).stream()
                .filter(o -> o.getPermission().getId().equals(permission.getId()))
                .findFirst()
                .orElseGet(UserPermissionOverride::new);

        override.setUser(user);
        override.setPermission(permission);
        override.setEffect(request.effect());
        override.setCreatedBy(currentUser.idOrNull());
        UserPermissionOverride saved = overrides.save(override);

        audit.recordFieldChanges(AuditService.ENTITY_APP_USER, id, List.of(
                AuditService.FieldChange.of("override:" + permission.getPermissionKey(), null, request.effect())));
        return Map.of("id", saved.getId(),
                "permissionId", permission.getId(),
                "permissionKey", permission.getPermissionKey(),
                "effect", saved.getEffect().name());
    }

    @DeleteMapping("/{userId}/overrides/{overrideId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> clearOverride(@PathVariable Long userId, @PathVariable Long overrideId) {
        overrides.findById(overrideId).ifPresent(override -> {
            audit.recordFieldChanges(AuditService.ENTITY_APP_USER, userId, List.of(
                    AuditService.FieldChange.of("override:" + override.getPermission().getPermissionKey(),
                            override.getEffect(), null)));
            overrides.delete(override);
        });
        return ResponseEntity.noContent().build();
    }

    // ---------------- helpers ----------------

    private Set<Role> resolveRoles(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return roles.findByName("Unassigned").map(Set::of).orElseGet(Set::of);
        }
        Set<Role> resolved = new LinkedHashSet<>(roles.findAllById(roleIds));
        if (resolved.size() != roleIds.size()) {
            throw new ApiExceptions.BadRequestException("One or more roles do not exist.");
        }
        return resolved;
    }

    private static List<String> roleNames(Set<Role> roles) {
        return roles.stream().map(Role::getName).sorted().toList();
    }

    private AppUser user(Long id) {
        return users.findById(id).orElseThrow(() -> new ApiExceptions.NotFoundException("User not found"));
    }

    private Map<String, Object> toView(AppUser user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("email", user.getEmail());
        view.put("authProvider", user.getAuthProvider().name());
        view.put("active", user.isActive());
        view.put("locked", user.isCurrentlyLocked());
        view.put("mustChangePassword", user.isMustChangePassword());
        view.put("lastLoginAt", user.getLastLoginAt());
        view.put("roles", user.getRoles().stream()
                .map(role -> Map.of("id", role.getId(), "name", role.getName()))
                .sorted(Comparator.comparing(m -> String.valueOf(m.get("name"))))
                .toList());
        return view;
    }
}
