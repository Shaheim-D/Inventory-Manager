package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.config.AppProperties;
import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Account lockout: {@code maxFailedAttempts} consecutive failures locks the
 * account for {@code lockoutMinutes} (5 / 15 by default, per MOP Part 3).
 * Counted per account in the database, so it survives restarts and applies
 * across however many app instances exist.
 */
@Service
public class LoginAttemptService {

    private final AppUserRepository users;
    private final AppProperties props;

    public LoginAttemptService(AppUserRepository users, AppProperties props) {
        this.users = users;
        this.props = props;
    }

    @Transactional
    public void recordFailure(String username) {
        users.findByUsernameIgnoreCase(username).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= props.getAuth().getMaxFailedAttempts()) {
                user.setLockedUntil(Instant.now().plus(props.getAuth().getLockoutMinutes(), ChronoUnit.MINUTES));
                user.setFailedLoginAttempts(0);
            }
            users.save(user);
        });
    }

    @Transactional
    public void recordSuccess(String username) {
        users.findByUsernameIgnoreCase(username).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(Instant.now());
            users.save(user);
        });
    }

    @Transactional(readOnly = true)
    public boolean isLocked(String username) {
        return users.findByUsernameIgnoreCase(username)
                .map(AppUser::isCurrentlyLocked)
                .orElse(false);
    }
}
