package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.LdapGroupRoleMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LdapGroupRoleMappingRepository extends JpaRepository<LdapGroupRoleMapping, Long> {

    List<LdapGroupRoleMapping> findByPluginId(Long pluginId);

    void deleteByPluginId(Long pluginId);
}
