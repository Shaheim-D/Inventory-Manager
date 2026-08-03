package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelationshipTypeRepository extends JpaRepository<RelationshipType, Long> {
    List<RelationshipType> findAllByOrderByNameAsc();
}
