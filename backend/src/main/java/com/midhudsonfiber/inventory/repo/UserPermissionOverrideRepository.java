package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.UserPermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPermissionOverrideRepository extends JpaRepository<UserPermissionOverride, Long> {
    List<UserPermissionOverride> findByUserId(Long userId);
}
