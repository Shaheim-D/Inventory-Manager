package com.midhudsonfiber.inventory.audit;

import com.midhudsonfiber.inventory.domain.AuditEvent;
import com.midhudsonfiber.inventory.repo.AuditEventRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every state change to an audited entity goes through here. One generic table,
 * one row per changed field, so the same mechanism serves the per-asset history
 * tab and the global audit screen without either needing special handling.
 */
@Service
public class AuditService {

    public static final String ENTITY_ASSET = "ASSET";
    public static final String ENTITY_LOCATION = "LOCATION";
    public static final String ENTITY_ASSET_CATEGORY = "ASSET_CATEGORY";
    public static final String ENTITY_APP_USER = "APP_USER";
    public static final String ENTITY_ROLE = "ROLE";
    public static final String ENTITY_BRANDING = "BRANDING";
    public static final String ENTITY_PURCHASE_ORDER = "PURCHASE_ORDER";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_LIFECYCLE_TRANSITION = "LIFECYCLE_TRANSITION";

    private final AuditEventRepository events;
    private final CurrentUser currentUser;

    public AuditService(AuditEventRepository events, CurrentUser currentUser) {
        this.events = events;
        this.currentUser = currentUser;
    }

    @Transactional
    public void recordCreate(String entityType, Long entityId, String summary) {
        events.save(event(entityType, entityId, ACTION_CREATE, null, null, summary, null));
    }

    @Transactional
    public void recordDelete(String entityType, Long entityId, String reason) {
        events.save(event(entityType, entityId, ACTION_DELETE, null, null, null, reason));
    }

    @Transactional
    public void recordLifecycleTransition(Long assetId, String from, String to, String reason) {
        events.save(event(ENTITY_ASSET, assetId, ACTION_LIFECYCLE_TRANSITION,
                "lifecycle_state", from, to, reason));
    }

    /** Records one row per field that actually changed; no-op when nothing did. */
    @Transactional
    public void recordFieldChanges(String entityType, Long entityId, List<FieldChange> changes) {
        List<AuditEvent> rows = new ArrayList<>();
        for (FieldChange change : changes) {
            if (Objects.equals(change.previousValue(), change.newValue())) continue;
            rows.add(event(entityType, entityId, ACTION_UPDATE,
                    change.fieldName(), change.previousValue(), change.newValue(), null));
        }
        if (!rows.isEmpty()) events.saveAll(rows);
    }

    private AuditEvent event(String entityType, Long entityId, String action,
                             String fieldName, String previous, String next, String reason) {
        AuditEvent audit = new AuditEvent();
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setUserId(currentUser.idOrNull());
        audit.setAction(action);
        audit.setFieldName(fieldName);
        audit.setPreviousValue(previous);
        audit.setNewValue(next);
        audit.setReason(reason);
        return audit;
    }

    public record FieldChange(String fieldName, String previousValue, String newValue) {
        public static FieldChange of(String fieldName, Object previous, Object next) {
            return new FieldChange(fieldName, asString(previous), asString(next));
        }

        private static String asString(Object value) {
            if (value == null) return null;
            if (value instanceof Object[] array) return java.util.Arrays.toString(array);
            return String.valueOf(value);
        }
    }
}
