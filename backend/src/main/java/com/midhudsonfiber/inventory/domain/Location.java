package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "location")
public class Location {

    /**
     * Four legal relationships, which is a genuinely closed set rather than a
     * vocabulary that grows -- so unlike location type this stays an enum, with
     * OTHER plus a description absorbing the exceptions.
     */
    public enum OwnershipType { COMPANY_OWNED, CUSTOMER_PREMISE, VENDOR, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_location_id")
    private Location parent;

    @Column(nullable = false)
    private String name;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "location_type_id")
    private LocationType locationType;

    @Column(name = "address_line1")
    private String addressLine1;

    private String city;
    private String state;
    private String zip;

    @Enumerated(EnumType.STRING)
    @Column(name = "ownership_type", nullable = false)
    private OwnershipType ownershipType;

    /** What "Other" means here. Required when ownership is OTHER, cleared otherwise. */
    @Column(name = "ownership_other_description")
    private String ownershipOtherDescription;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Location getParent() { return parent; }
    public void setParent(Location parent) { this.parent = parent; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocationType getLocationType() { return locationType; }
    public void setLocationType(LocationType t) { this.locationType = t; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String a) { this.addressLine1 = a; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getZip() { return zip; }
    public void setZip(String zip) { this.zip = zip; }
    public OwnershipType getOwnershipType() { return ownershipType; }
    public void setOwnershipType(OwnershipType o) { this.ownershipType = o; }
    public String getOwnershipOtherDescription() { return ownershipOtherDescription; }
    public void setOwnershipOtherDescription(String v) { this.ownershipOtherDescription = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
