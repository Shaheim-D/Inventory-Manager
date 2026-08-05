package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * "Everyone in this directory group holds this role."
 *
 * <p>Read by the directory-sync plugin (Phase 8 §1) and by nothing in the login
 * path. Authentication decides whether somebody gets in; this decides what they
 * can do once they have, and the two are kept apart deliberately — a directory
 * sync that fails must never be able to lock anybody out.
 */
@Entity
@Table(name = "ldap_group_role_mapping")
public class LdapGroupRoleMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", nullable = false)
    private Long pluginId;

    @Column(name = "group_identifier", nullable = false)
    private String groupIdentifier;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    public Long getId() { return id; }

    public Long getPluginId() { return pluginId; }
    public void setPluginId(Long pluginId) { this.pluginId = pluginId; }

    public String getGroupIdentifier() { return groupIdentifier; }
    public void setGroupIdentifier(String groupIdentifier) { this.groupIdentifier = groupIdentifier; }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
}
