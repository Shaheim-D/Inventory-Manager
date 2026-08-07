package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * RADIUS/NPS sign-in configuration. One row, id fixed at 1 by a CHECK, the same
 * shape {@code mail_settings} and {@code branding} already use.
 *
 * <p>The servers themselves are rows in {@code radius_server} (V27), because
 * there are two of them and a second is a row rather than a second set of
 * columns. What stays here is what applies to all of them: whether sign-in is
 * on at all, the timeout and retry budget, and the NAS identifier this
 * application presents.
 */
@Entity
@Table(name = "radius_settings")
public class RadiusSettings {

    @Id
    private Short id = 1;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 5;

    @Column(nullable = false)
    private int retries = 1;

    /**
     * What this application calls itself to the RADIUS server. NPS network
     * policies routinely match on it, so a blank one is the reason a correct
     * password still gets rejected.
     */
    @Column(name = "nas_identifier")
    private String nasIdentifier;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }

    public String getNasIdentifier() { return nasIdentifier; }
    public void setNasIdentifier(String nasIdentifier) { this.nasIdentifier = nasIdentifier; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
