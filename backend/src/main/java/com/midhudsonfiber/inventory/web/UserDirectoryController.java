package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The minimum needed to offer people as choices — id, username, email — for
 * anyone who can read assets. Deliberately separate from the Users admin API,
 * which needs {@code user:manage} and exposes roles, lockout state, and login
 * history: assigning a laptop to someone should not require the ability to
 * administer their account.
 */
@RestController
@RequestMapping("/api/users")
public class UserDirectoryController {

    private final AppUserRepository users;

    public UserDirectoryController(AppUserRepository users) {
        this.users = users;
    }

    @GetMapping("/assignable")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> assignable() {
        return users.findAllByOrderByUsernameAsc().stream()
                .filter(AppUser::isActive)
                .map(user -> Map.<String, Object>of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail() == null ? "" : user.getEmail()))
                .toList();
    }
}
