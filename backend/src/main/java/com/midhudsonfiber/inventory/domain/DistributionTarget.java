package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * Who a rule tells.
 *
 * <p>Two kinds, both required by the Phase 2 stakeholder decision. A ROLE target
 * is resolved to its members <em>at send time</em>, never snapshotted — someone
 * who joins the Asset Manager role today starts getting warranty alerts today,
 * and someone who leaves stops, with nobody editing a recipient list. An EMAIL
 * target is a fixed address for the cases a role cannot express, like a shared
 * purchasing inbox or an external accountant.
 *
 * <p>A CHECK insists on exactly one of the two being set, so there is no such
 * thing as a target that means both or neither.
 */
@Entity
@Table(name = "distribution_target")
public class DistributionTarget {

    public enum TargetType { EMAIL, ROLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_rule_id")
    private NotificationRule rule;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "email_address")
    private String emailAddress;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;

    public Long getId() { return id; }
    public NotificationRule getRule() { return rule; }
    public void setRule(NotificationRule rule) { this.rule = rule; }
    public TargetType getTargetType() { return targetType; }
    public void setTargetType(TargetType targetType) { this.targetType = targetType; }
    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
