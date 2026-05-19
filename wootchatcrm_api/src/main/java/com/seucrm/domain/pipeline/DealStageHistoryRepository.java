package com.seucrm.domain.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DealStageHistoryRepository extends JpaRepository<DealStageHistory, UUID> {
    List<DealStageHistory> findByDealIdOrderByCreatedAtAsc(UUID dealId);
}
