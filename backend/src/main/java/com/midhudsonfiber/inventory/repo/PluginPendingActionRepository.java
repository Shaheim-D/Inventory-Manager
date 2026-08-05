package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.PluginPendingAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginPendingActionRepository extends JpaRepository<PluginPendingAction, Long> {

    List<PluginPendingAction> findByPluginIdAndStatusOrderByIdAsc(
            Long pluginId, PluginPendingAction.Status status);

    long countByPluginIdAndStatus(Long pluginId, PluginPendingAction.Status status);

    /**
     * The idempotency guard: a re-run must top up the existing proposal rather
     * than stack a second one for the same record.
     */
    Optional<PluginPendingAction> findByPluginIdAndExternalIdentifierAndStatus(
            Long pluginId, String externalIdentifier, PluginPendingAction.Status status);
}
