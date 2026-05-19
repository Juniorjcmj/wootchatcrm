package com.seucrm.domain.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByTenantIdAndActiveOrderByIsDefaultDesc(UUID tenantId, Boolean active);
    Optional<Pipeline> findByTenantIdAndIsDefaultTrue(UUID tenantId);
    Optional<Pipeline> findByIdAndTenantId(UUID id, UUID tenantId);
}
