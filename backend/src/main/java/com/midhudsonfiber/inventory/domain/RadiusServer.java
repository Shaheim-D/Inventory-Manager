package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One RADIUS server. Tried in {@code ordinal} order, so 1 is the primary and 2
 * the secondary, and a third would simply be another row.
 *
 * <p>{@code sharedSecretEnc} is AES-256-GCM ciphertext produced by
 * {@code SecretCipher}. Nothing in the application holds the plaintext beyond
 * the moment it is used, and no endpoint ever returns it.
 */
@Entity
@Table(name = "radius_server")
public class RadiusServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port = 1812;

    @Column(name = "shared_secret_enc")
    private String sharedSecretEnc;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getSharedSecretEnc() { return sharedSecretEnc; }
    public void setSharedSecretEnc(String sharedSecretEnc) { this.sharedSecretEnc = sharedSecretEnc; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** What to call it on screen and in a log line. Never includes the secret. */
    public String label() {
        return host + ":" + port;
    }
}
