package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.LdapRoleMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LdapRoleMappingRepository extends JpaRepository<LdapRoleMapping, Long> {
    List<LdapRoleMapping> findAllByOrderByGroupValueAsc();
}
