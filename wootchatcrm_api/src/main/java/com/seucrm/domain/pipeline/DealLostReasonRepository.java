package com.seucrm.domain.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DealLostReasonRepository extends JpaRepository<DealLostReason, UUID> {
    List<DealLostReason> findByTenantIdAndActive(UUID tenantId, Boolean active);
}
