package com.seucrm.domain.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutomationRepository extends JpaRepository<Automation, UUID> {
    List<Automation> findByTenantIdAndStatus(UUID tenantId, Automation.AutomationStatus status);
    List<Automation> findByTenantIdAndTriggerTypeAndStatus(
        UUID tenantId, 
        Automation.TriggerType triggerType, 
        Automation.AutomationStatus status
    );
    Optional<Automation> findByIdAndTenantId(UUID id, UUID tenantId);
}
