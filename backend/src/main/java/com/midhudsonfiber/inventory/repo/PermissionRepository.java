package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByPermissionKey(String permissionKey);
    List<Permission> findAllByOrderByPermissionKeyAsc();
}
