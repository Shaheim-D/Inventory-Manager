package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    Page<AuditEvent> findByEntityTypeAndEntityId(String entityType, Long entityId, Pageable pageable);
}
