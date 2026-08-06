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
 * Just-in-time provisioning for accounts authenticated somewhere else. A
 * first-time RADIUS user gets an app_user row with the <b>Unassigned</b> role --
 * zero permissions -- until somebody grants real access.
 *
 * <p>This never writes password_hash, failed_login_attempts or locked_until for
 * such an account: whether the credentials were right is NPS's business, not
 * ours, and a local lockout counter on an account with no local password would
 * only ever be wrong.
 *
 * <p>Named for what it does rather than for the protocol behind it. It was
 * DirectoryUserProvisioner while LDAP was the only way in; the logic never
 * mentioned LDAP and did not change when RADIUS replaced it.
 */
@Service
public class ExternalUserProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ExternalUserProvisioner.class);
    private static final String DEFAULT_ROLE = "Unassigned";

    private final AppUserRepository users;
    private final RoleRepository roles;

    public ExternalUserProvisioner(AppUserRepository users, RoleRepository roles) {
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
                    log.info("JIT-provisioned external user '{}' ({}) with the {} role", username, provider, DEFAULT_ROLE);
                    return users.save(user);
                });
    }
}
