package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.AuditEvent;
import com.midhudsonfiber.inventory.repo.AuditEventRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The global, unscoped audit feed — same rows as the per-asset tab, filterable. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditEventRepository events;

    public AuditController(AuditEventRepository events) {
        this.events = events;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.AUDIT_VIEW + "')")
    public Map<String, Object> list(@RequestParam(required = false) String entityType,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {

        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (entityType != null && !entityType.isBlank()) predicates.add(cb.equal(root.get("entityType"), entityType));
            if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
            if (action != null && !action.isBlank()) predicates.add(cb.equal(root.get("action"), action));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditEvent> result = events.findAll(spec,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "occurredAt", "id")));

        return Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages());
    }
}
