package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.Role;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Just-in-time provisioning for directory logins. A first-time LDAP/AD user gets
 * an app_user row with the <b>Unassigned</b> role -- zero permissions -- until an
 * Administrator grants real access. This never writes password_hash,
 * failed_login_attempts, or locked_until for a directory account: authentication
 * against the directory is the directory's business, not ours.
 */
@Service
public class DirectoryUserProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DirectoryUserProvisioner.class);
    private static final String DEFAULT_ROLE = "Unassigned";

    private final AppUserRepository users;
    private final RoleRepository roles;

    public DirectoryUserProvisioner(AppUserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @Transactional
    public AppUser provision(String username, AppUser.AuthProvider provider, String externalId, String email) {
        return users.findByUsernameIgnoreCase(username)
                .map(existing -> {
                    if (externalId != null) existing.setExternalId(externalId);
                    if (email != null) existing.setEmail(email);
                    return users.save(existing);
                })
                .orElseGet(() -> {
                    AppUser user = new AppUser();
                    user.setUsername(username);
                    user.setAuthProvider(provider);
                    user.setExternalId(externalId);
                    user.setEmail(email);
                    user.setActive(true);
                    roles.findByName(DEFAULT_ROLE).ifPresent(r -> user.setRoles(Set.<Role>of(r)));
                    log.info("JIT-provisioned directory user '{}' ({}) with the {} role", username, provider, DEFAULT_ROLE);
                    return users.save(user);
                });
    }
}
