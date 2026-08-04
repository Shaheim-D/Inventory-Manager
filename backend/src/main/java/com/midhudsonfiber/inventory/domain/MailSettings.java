package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The SMTP relay, configured in the application rather than redeployed.
 *
 * <p>One row, id fixed at 1, the same shape {@link Branding} uses. With
 * {@code enabled} false the system still notifies — in-app only — so email is
 * an addition to delivery rather than a prerequisite for it.
 */
@Entity
@Table(name = "mail_settings")
public class MailSettings {

    @Id
    private Short id = 1;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    private String host;

    private Integer port;

    private String username;

    /**
     * Stored as given, because SMTP AUTH has to present it — hashing is not an
     * option for a credential that must be replayed. It never leaves through the
     * API: the settings endpoint reports whether one is set, never what it is.
     */
    private String password;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "start_tls", nullable = false)
    private boolean startTls = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /** True when there is somewhere to send and somebody has switched it on. */
    public boolean isUsable() {
        return enabled && host != null && !host.isBlank() && port != null && fromAddress != null;
    }

    public Short getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public boolean isStartTls() { return startTls; }
    public void setStartTls(boolean startTls) { this.startTls = startTls; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
