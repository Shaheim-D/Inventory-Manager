package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard widgets, each independently permission-gated: a widget the viewer
 * cannot see is simply not in the response, the same rule the rest of the
 * platform applies to fields.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final EntityManager entityManager;
    private final CurrentUser currentUser;

    public DashboardController(EntityManager entityManager, CurrentUser currentUser) {
        this.entityManager = entityManager;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.DASHBOARD_VIEW + "')")
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> widgets = new LinkedHashMap<>();

        if (currentUser.has(PermissionKeys.ASSET_READ)) {
            widgets.put("assetsByCategory", rows("""
                    SELECT c.name, count(a.id)
                    FROM asset a JOIN asset_category c ON c.id = a.asset_category_id
                    WHERE a.is_deleted = FALSE
                    GROUP BY c.name ORDER BY count(a.id) DESC
                    """));
            widgets.put("assetsByLifecycleState", rows("""
                    SELECT s.name, count(a.id)
                    FROM asset a JOIN lifecycle_state s ON s.id = a.lifecycle_state_id
                    WHERE a.is_deleted = FALSE
                    GROUP BY s.name ORDER BY count(a.id) DESC
                    """));
            widgets.put("totalAssets", scalar("SELECT count(*) FROM asset WHERE is_deleted = FALSE"));
        }

        if (currentUser.has(PermissionKeys.ASSET_WRITE)) {
            // Live computed staleness count -- there is no staging table behind this,
            // the asset row already is the truth (Staleness design §4).
            widgets.put("staleAssetCount", scalar("""
                    SELECT count(*)
                    FROM asset a
                    JOIN asset_category c ON c.id = a.asset_category_id
                    JOIN lifecycle_state s ON s.id = a.lifecycle_state_id
                    WHERE a.is_deleted = FALSE
                      AND c.verification_interval_days IS NOT NULL
                      AND s.name NOT IN ('Disposed', 'Retired')
                      AND a.last_verified_at < now() - (c.verification_interval_days || ' days')::interval
                    """));
        }

        if (currentUser.has(PermissionKeys.ASSET_READ)) {
            widgets.put("warrantyExpiringSoon", scalar("""
                    SELECT count(*) FROM asset
                    WHERE is_deleted = FALSE
                      AND warranty_expiration IS NOT NULL
                      AND warranty_expiration BETWEEN current_date AND current_date + INTERVAL '90 days'
                    """));
        }

        if (currentUser.has(PermissionKeys.AUDIT_VIEW)) {
            widgets.put("recentAuditCount", scalar(
                    "SELECT count(*) FROM audit_event WHERE occurred_at > now() - INTERVAL '7 days'"));
        }

        return widgets;
    }

    private Long scalar(String sql) {
        Object value = entityManager.createNativeQuery(sql).getSingleResult();
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String sql) {
        List<Object[]> results = entityManager.createNativeQuery(sql).getResultList();
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Object[] row : results) {
            mapped.add(Map.of("label", String.valueOf(row[0]), "count", ((Number) row[1]).longValue()));
        }
        return mapped;
    }
}
