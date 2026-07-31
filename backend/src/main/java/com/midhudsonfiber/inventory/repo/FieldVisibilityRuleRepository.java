package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.FieldVisibilityRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FieldVisibilityRuleRepository extends JpaRepository<FieldVisibilityRule, Long> {
    List<FieldVisibilityRule> findByEntityType(FieldVisibilityRule.EntityType entityType);
}
