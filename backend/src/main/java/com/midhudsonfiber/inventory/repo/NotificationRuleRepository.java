package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.NotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {

    /**
     * Active rules for a trigger, with their targets already loaded — dispatch
     * runs outside a view and would otherwise fail on the lazy collection.
     */
    @Query("""
            SELECT DISTINCT r FROM NotificationRule r
            LEFT JOIN FETCH r.targets t
            LEFT JOIN FETCH t.role
            LEFT JOIN FETCH r.category
            WHERE r.triggerType = :triggerType AND r.active = true
            """)
    List<NotificationRule> findActiveFor(NotificationRule.TriggerType triggerType);

    @Query("""
            SELECT DISTINCT r FROM NotificationRule r
            LEFT JOIN FETCH r.targets t
            LEFT JOIN FETCH t.role
            LEFT JOIN FETCH r.category
            """)
    List<NotificationRule> findAllWithTargets();
}
