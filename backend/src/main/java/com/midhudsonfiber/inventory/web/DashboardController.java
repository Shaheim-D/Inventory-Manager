package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three figures above the asset list on the home page, each independently
 * permission-gated: one the viewer may not see is simply not in the response,
 * the same rule the rest of the platform applies to fields.
 *
 * <p>The breakdowns that used to be here -- assets by category, assets by
 * lifecycle state, purchase orders by status -- are gone. They were a bar chart
 * of things the asset list already filters by, so the answer to every question
 * they raised was "go and look at the assets", which is now the same screen.
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
            widgets.put("totalAssets", scalar("SELECT count(*) FROM asset WHERE is_deleted = FALSE"));
            widgets.put("warrantyExpiringSoon", scalar("""
                    SELECT count(*) FROM asset
                    WHERE is_deleted = FALSE
                      AND warranty_expiration IS NOT NULL
                      AND warranty_expiration BETWEEN current_date AND current_date + INTERVAL '90 days'
                    """));
        }

        if (currentUser.has(PermissionKeys.PURCHASE_ORDER_VIEW)) {
            // "Approved, and the equipment is not all here yet" -- the three
            // states between somebody agreeing to buy something and it arriving:
            // approved but not placed, placed but nothing delivered, and partly
            // delivered. Everything else is either not agreed yet (DRAFT,
            // SUBMITTED), finished (RECEIVED), or dead (REJECTED, CANCELLED).
            //
            // This replaced a breakdown of every status. A count of drafts is
            // somebody's unfinished sentence; this is the number that means
            // there is equipment owed to you.
            widgets.put("activePurchaseOrders", scalar("""
                    SELECT count(*) FROM purchase_order
                    WHERE status IN ('APPROVED', 'ORDERED', 'PARTIALLY_RECEIVED')
                    """));
        }

        return widgets;
    }

    private Long scalar(String sql) {
        Object value = entityManager.createNativeQuery(sql).getSingleResult();
        return ((Number) value).longValue();
    }

}
