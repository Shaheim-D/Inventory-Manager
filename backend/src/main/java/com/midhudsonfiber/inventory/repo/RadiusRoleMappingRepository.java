package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.RadiusRoleMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RadiusRoleMappingRepository extends JpaRepository<RadiusRoleMapping, Long> {

    List<RadiusRoleMapping> findAllByOrderByAttributeValueAsc();
}
