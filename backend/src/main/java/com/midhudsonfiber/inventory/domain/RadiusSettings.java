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
 * <p>{@code sharedSecretRef} holds the <b>name of an environment variable</b>,
 * never the secret. That is the rule the plugin framework already follows and it
 * matters more here: this table is in every backup, and a backup is readable by
 * whoever holds it. {@code SecretResolver} reads the value when a sign-in
 * actually needs it.
 */
@Entity
@Table(name = "radius_settings")
public class RadiusSettings {

    @Id
    private Short id = 1;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    private String host;

    @Column(nullable = false)
    private int port = 1812;

    /** The NAME of the environment variable holding the shared secret. */
    @Column(name = "shared_secret_ref")
    private String sharedSecretRef;

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

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getSharedSecretRef() { return sharedSecretRef; }
    public void setSharedSecretRef(String sharedSecretRef) { this.sharedSecretRef = sharedSecretRef; }

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
