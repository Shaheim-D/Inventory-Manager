package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * LDAP / Active Directory sign-in, configured in the application.
 *
 * <p>One row, id fixed at 1 — the shape {@link Branding}, {@code MailSettings},
 * {@code RadiusSettings} and {@code BackupSettings} already use.
 *
 * <p>This sits <em>beside</em> RADIUS rather than replacing it. Both may be
 * enabled: local accounts are tried first, then RADIUS, then LDAP, so no
 * directory outage can take away the local account somebody needs to fix it.
 *
 * <p>The reason to have both is group membership. A RADIUS reply carries only
 * what an administrator configured NPS to send; {@code memberOf} is simply
 * there, which is what lets a new starter land in the right role because of the
 * group they are already in.
 */
@Entity
@Table(name = "ldap_settings")
public class LdapSettings {

    /**
     * How the connection is protected. A simple bind sends the password, so
     * {@code NONE} is a lab setting the screen warns about rather than hides.
     */
    public enum Transport { NONE, STARTTLS, LDAPS }

    @Id
    private Short id = 1;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    private String host;

    private int port = 636;

    @Enumerated(EnumType.STRING)
    private Transport transport = Transport.LDAPS;

    @Column(name = "user_search_base")
    private String userSearchBase;

    /** {@code {0}} is the username as typed. Active Directory's default. */
    @Column(name = "user_search_filter", nullable = false)
    private String userSearchFilter = "(sAMAccountName={0})";

    @Column(name = "group_attribute", nullable = false)
    private String groupAttribute = "memberOf";

    /**
     * Bind straight as {@code username@suffix}, the way Active Directory
     * allows. No service account exists, so none can leak, and the search for
     * the person's groups runs as the person who just proved who they are.
     */
    @Column(name = "upn_suffix")
    private String upnSuffix;

    /** A read-only service account, for directories where users cannot search. */
    @Column(name = "bind_dn")
    private String bindDn;

    /** AES-256-GCM, never readable. Same treatment as a RADIUS shared secret. */
    @Column(name = "bind_password_enc")
    private String bindPasswordEnc;

    @Column(name = "connect_timeout_seconds", nullable = false)
    private int connectTimeoutSeconds = 5;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * True when this is switched on and has enough to attempt a sign-in.
     *
     * <p>Mirrors the CHECK constraint, so the provider can decline quietly and
     * the API can refuse with a sentence, rather than either letting a
     * half-configured directory produce a failure on every attempt.
     */
    public boolean isUsable() {
        return enabled
                && notBlank(host)
                && notBlank(userSearchBase)
                && (notBlank(upnSuffix) || notBlank(bindDn));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public Short getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public Transport getTransport() { return transport; }
    public void setTransport(Transport transport) { this.transport = transport; }
    public String getUserSearchBase() { return userSearchBase; }
    public void setUserSearchBase(String userSearchBase) { this.userSearchBase = userSearchBase; }
    public String getUserSearchFilter() { return userSearchFilter; }
    public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }
    public String getGroupAttribute() { return groupAttribute; }
    public void setGroupAttribute(String groupAttribute) { this.groupAttribute = groupAttribute; }
    public String getUpnSuffix() { return upnSuffix; }
    public void setUpnSuffix(String upnSuffix) { this.upnSuffix = upnSuffix; }
    public String getBindDn() { return bindDn; }
    public void setBindDn(String bindDn) { this.bindDn = bindDn; }
    public String getBindPasswordEnc() { return bindPasswordEnc; }
    public void setBindPasswordEnc(String bindPasswordEnc) { this.bindPasswordEnc = bindPasswordEnc; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int seconds) { this.connectTimeoutSeconds = seconds; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
