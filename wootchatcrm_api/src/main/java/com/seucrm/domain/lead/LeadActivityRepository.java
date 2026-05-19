package com.seucrm.domain.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeadActivityRepository extends JpaRepository<LeadActivity, UUID> {
    List<LeadActivity> findByLeadIdOrderByDueAtAsc(UUID leadId);
    List<LeadActivity> findByTenantIdAndDoneAtIsNullOrderByDueAtAsc(UUID tenantId);
}
