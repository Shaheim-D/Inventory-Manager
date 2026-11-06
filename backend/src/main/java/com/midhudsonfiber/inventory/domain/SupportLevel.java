package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * A service tier a support provider sells — Dell ProSupport Plus, Cisco
 * SmartNet 24x7x4.
 *
 * <p>A table rather than free text for the reason {@link LocationType} is one:
 * Dell's tiers and Cisco's are different vocabularies, so a fixed list in code
 * would be wrong for whichever vendor was not consulted, while free text means
 * "ProSupport Plus", "Prosupport+" and "PS Plus" all end up in the column and
 * no report can group them. Adding the next tier is a row.
 *
 * <p>Deactivated rather than deleted once something references it, the same way
 * a location type is: a tier nobody sells any more still describes the contract
 * an asset was bought under.
 */
@Entity
@Table(name = "support_level")
public class SupportLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /**
     * Who sells this tier. Nullable because a level may be generic, but when it
     * is set the picker can group Dell's tiers apart from Cisco's rather than
     * offering one undifferentiated list.
     */
    private String provider;

    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 500;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /** "ProSupport Plus (Dell)" — what a picker shows when both are known. */
    public String displayLabel() {
        return provider == null || provider.isBlank() ? name : name + " (" + provider + ")";
    }
}
