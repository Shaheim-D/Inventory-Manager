package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * Marks a core asset column as meaningful for one category, so a Vehicle form
 * does not ask for a firmware version.
 *
 * <p>Not to be confused with {@code field_visibility_rule}: that decides whether
 * a <em>viewer</em> may see a field and answers by omitting it from the API
 * response. This decides whether a field means anything for a <em>kind of
 * thing</em>, which is the same for everybody and is never a security boundary.
 */
@Entity
@Table(name = "category_core_field")
public class CategoryCoreField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @Column(name = "core_field_name", nullable = false)
    private String coreFieldName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public String getCoreFieldName() { return coreFieldName; }
    public void setCoreFieldName(String coreFieldName) { this.coreFieldName = coreFieldName; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
