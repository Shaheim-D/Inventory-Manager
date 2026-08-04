package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    List<AppUser> findAllByOrderByUsernameAsc();

    /**
     * Who is in a role right now. Notification targets are resolved through this
     * at send time rather than snapshotted, so joining or leaving a role changes
     * who gets told without anyone editing a list.
     *
     * <p>Inactive accounts are excluded: someone who has left should not keep
     * accruing alerts, and their unread count is nobody's to clear.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u FROM AppUser u JOIN u.roles r
            WHERE r.id = :roleId AND u.active = true
            ORDER BY u.username
            """)
    List<AppUser> findActiveByRoleId(Long roleId);
}
