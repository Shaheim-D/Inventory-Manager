package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A custom report somebody built and kept (V9).
 *
 * <p>Purely a convenience. The builder works with no saved definition at all,
 * which the design was explicit about — saving is for the vendor ask that comes
 * round every quarter, never a gate in front of building one ad hoc.
 *
 * <p>Fields and filters are JSONB and validated at the application layer, the
 * same pattern as {@code asset.custom_fields}: a report's shape is a list of
 * field keys, and a column per possible key would be a schema change every time
 * a field is added.
 *
 * <p>Nothing here is a security boundary. A definition can name a field the
 * person running it may not see, because the person running it need not be the
 * person who saved it — so the fields are re-checked against the runner's own
 * visibility every single time it is run, never trusted from the row.
 */
@Entity
@Table(name = "saved_report_definition")
public class SavedReportDefinition {

    public enum EntityType { ASSET, PURCHASE_ORDER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private EntityType entityType = EntityType.ASSET;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selected_fields", nullable = false)
    private List<String> selectedFields = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_config", nullable = false)
    private Map<String, Object> filterConfig = new LinkedHashMap<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public EntityType getEntityType() { return entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }

    public List<String> getSelectedFields() { return selectedFields; }
    public void setSelectedFields(List<String> selectedFields) { this.selectedFields = selectedFields; }

    public Map<String, Object> getFilterConfig() { return filterConfig; }
    public void setFilterConfig(Map<String, Object> filterConfig) { this.filterConfig = filterConfig; }

    public Instant getCreatedAt() { return createdAt; }
}
