package com.midhudsonfiber.inventory.config;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Creates the first Administrator so a fresh deployment is reachable. Runs only
 * when app_user is empty, so it can never resurrect or overwrite an account on a
 * running system. If no initial password was configured, one is generated and
 * logged once — the account is flagged must-change-password either way.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public BootstrapAdminInitializer(AppUserRepository users, RoleRepository roles,
                                     PasswordEncoder passwordEncoder, AppProperties props) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;

        String username = props.getBootstrapAdmin().getUsername();
        String password = props.getBootstrapAdmin().getPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = generatePassword();
        }

        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setAuthProvider(AppUser.AuthProvider.LOCAL);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setMustChangePassword(true);
        admin.setActive(true);
        roles.findByName("Administrator").ifPresent(role -> admin.setRoles(Set.of(role)));
        users.save(admin);

        if (generated) {
            log.warn("""

                    =====================================================================
                     No users existed, so a bootstrap Administrator was created.
                       username: {}
                       password: {}
                     This password is shown once and must be changed at first sign-in.
                     Set APP_ADMIN_INITIAL_PASSWORD to choose it yourself instead.
                    =====================================================================""",
                    username, password);
        } else {
            log.info("Bootstrap Administrator '{}' created from APP_ADMIN_INITIAL_PASSWORD "
                    + "(must be changed at first sign-in).", username);
        }
    }

    private static String generatePassword() {
        byte[] entropy = new byte[18];
        new SecureRandom().nextBytes(entropy);
        return "Im" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy) + "1";
    }
}
