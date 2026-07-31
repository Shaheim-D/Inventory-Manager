package com.midhudsonfiber.inventory.security;

/**
 * The permission catalog as constants. These are references to rows in the
 * {@code permission} table, not a second source of truth -- authorization always
 * resolves against the database. Having them named here just stops typos in
 * {@code @PreAuthorize} expressions from becoming silent always-deny bugs.
 */
public final class PermissionKeys {
    private PermissionKeys() {}

    public static final String ASSET_READ = "asset:read";
    public static final String ASSET_WRITE = "asset:write";
    public static final String ASSET_DELETE = "asset:delete";
    public static final String ASSET_COST_VIEW = "asset:cost:view";
    public static final String ASSET_VEHICLE_DETAILS_VIEW = "asset:vehicle:details:view";

    public static final String LOCATION_READ = "location:read";
    public static final String LOCATION_WRITE = "location:write";

    public static final String RELATIONSHIP_MANAGE = "relationship:manage";
    public static final String ATTACHMENT_UPLOAD = "attachment:upload";
    public static final String ATTACHMENT_DELETE = "attachment:delete";

    public static final String PURCHASE_ORDER_VIEW = "purchase_order:view";
    public static final String PURCHASE_ORDER_CREATE = "purchase_order:create";
    public static final String PURCHASE_ORDER_APPROVE = "purchase_order:approve";
    public static final String PURCHASE_ORDER_RECEIVE = "purchase_order:receive";
    public static final String PURCHASE_ORDER_COST_VIEW = "purchase_order:cost:view";

    public static final String CATEGORY_MANAGE = "category:manage";
    public static final String ROLE_MANAGE = "role:manage";
    public static final String USER_MANAGE = "user:manage";
    public static final String PLUGIN_MANAGE = "plugin:manage";
    public static final String NOTIFICATION_RULE_MANAGE = "notification_rule:manage";

    public static final String REPORT_VIEW = "report:view";
    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String AUDIT_VIEW = "audit:view";

    public static final String IMPORT_RUN = "import:run";

    /** Added in V10 -- a plain catalog insert, exactly as Phase 7 §3 anticipated. */
    public static final String BRANDING_MANAGE = "branding:manage";
}
