package com.seucrm.domain.pipeline;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {
    Optional<Deal> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
        SELECT d FROM Deal d 
        WHERE d.pipeline.id = :pipelineId 
          AND d.tenantId = :tenantId 
          AND d.status = 'OPEN' 
        ORDER BY d.stage.orderIndex ASC, d.createdAt DESC
    """)
    List<Deal> findBoardDeals(@Param("pipelineId") UUID pipelineId, @Param("tenantId") UUID tenantId);

    Page<Deal> findByTenantIdAndLeadId(UUID tenantId, UUID leadId, Pageable pageable);

    List<Deal> findByTenantIdAndLeadIdOrderByEnteredStageAtDesc(UUID tenantId, UUID leadId);

    @Query("""
        SELECT COALESCE(SUM(d.value), 0) 
        FROM Deal d 
        WHERE d.pipeline.id = :pipelineId 
          AND d.tenantId = :tenantId 
          AND d.status = :status
    """)
    BigDecimal sumValueByPipelineAndStatus(
        @Param("pipelineId") UUID pipelineId,
        @Param("tenantId") UUID tenantId,
        @Param("status") Deal.DealStatus status
    );
}
