package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * A kind of link that may be drawn between two assets — "Installed In",
 * "Connected To", and so on.
 *
 * <p>A vocabulary table rather than an enum precisely so a new kind of link is
 * one INSERT rather than a deployment. Names read source → target: an SFP is
 * <em>installed in</em> its host switch, not the reverse.
 */
@Entity
@Table(name = "relationship_type")
public class RelationshipType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
