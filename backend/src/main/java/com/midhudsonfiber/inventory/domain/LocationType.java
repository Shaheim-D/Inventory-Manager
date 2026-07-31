package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * What kind of place a location is. A table rather than a CHECK constraint so
 * an administrator can add one without a migration -- the same shape
 * {@link LifecycleState} and relationship types already use.
 */
@Entity
@Table(name = "location_type")
public class LocationType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
