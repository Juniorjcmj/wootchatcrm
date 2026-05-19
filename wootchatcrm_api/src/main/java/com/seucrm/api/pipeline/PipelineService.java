package com.seucrm.api.pipeline;

import com.seucrm.config.TenantContext;
import com.seucrm.domain.pipeline.*;
import com.seucrm.domain.user.User;
import com.seucrm.shared.exception.BusinessException;
import com.seucrm.shared.pagination.PageResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

record CreatePipelineRequest(@NotBlank String name, String description, Boolean isDefault) {}
record CreateDealRequest(@NotBlank String title, @NotNull UUID leadId, @NotNull UUID stageId, BigDecimal value, UUID assignedTo, LocalDate expectedCloseAt) {}
record CreateStageRequest(@NotBlank String name, String color, Integer slaHours, Boolean isWon, Boolean isLost) {}
record UpdateStageRequest(String name, String color, Integer slaHours, Short orderIndex, Boolean isWon, Boolean isLost) {}
record ReorderStagesRequest(@NotNull List<UUID> stageIds) {}
record MoveDealRequest(@NotNull UUID toStageId) {}
record PipelineResponse(UUID id, String name, String description, Boolean isDefault, List<StageResponse> stages) {}
record StageResponse(UUID id, String name, Short orderIndex, String color, Integer slaHours, Boolean isWon, Boolean isLost) {}
record DealResponse(UUID id, UUID leadId, String leadName, UUID pipelineId, UUID stageId, String title, BigDecimal value, String status, UUID assignedTo, Instant enteredStageAt) {
    static DealResponse from(Deal d) {
        return new DealResponse(d.getId(), d.getLeadId(), null, d.getPipeline().getId(), d.getStage().getId(), d.getTitle(), d.getValue(), d.getStatus().name(), d.getAssignedTo(), d.getEnteredStageAt());
    }
    static DealResponse from(Deal d, String leadName) {
        return new DealResponse(d.getId(), d.getLeadId(), leadName, d.getPipeline().getId(), d.getStage().getId(), d.getTitle(), d.getValue(), d.getStatus().name(), d.getAssignedTo(), d.getEnteredStageAt());
    }
}
record BoardResponse(UUID pipelineId, String pipelineName, List<ColumnResponse> columns) {}
record ColumnResponse(StageResponse stage, List<DealResponse> deals, BigDecimal totalValue) {}

@Service
@RequiredArgsConstructor
public class PipelineService {

    private final PipelineRepository pipelineRepo;
    private final DealRepository dealRepo;
    private final DealStageHistoryRepository historyRepo;
    private final com.seucrm.domain.lead.LeadRepository leadRepo;

    @Transactional(readOnly = true)
    public List<PipelineResponse> listPipelines() {
        UUID tenantId = UUID.fromString(TenantContext.get());
        return pipelineRepo.findByTenantIdAndActiveOrderByIsDefaultDesc(tenantId, true)
            .stream().map(this::toPipelineResponse).toList();
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoard(UUID pipelineId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Pipeline pipeline = pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Pipeline", pipelineId));
        
        List<Deal> deals = dealRepo.findBoardDeals(pipelineId, tenantId);
        Map<UUID, List<Deal>> dealsByStage = deals.stream()
            .collect(Collectors.groupingBy(d -> d.getStage().getId()));

        // Lookup leadName in batch para não dar N+1 ao popular os cards.
        java.util.Set<UUID> leadIds = deals.stream().map(Deal::getLeadId).collect(Collectors.toSet());
        Map<UUID, String> leadNames = leadIds.isEmpty()
            ? Map.of()
            : leadRepo.findAllById(leadIds).stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .collect(Collectors.toMap(com.seucrm.domain.lead.Lead::getId, com.seucrm.domain.lead.Lead::getName));

        List<ColumnResponse> columns = pipeline.getStages().stream()
            .map(stage -> {
                List<Deal> stageDeals = dealsByStage.getOrDefault(stage.getId(), List.of());
                BigDecimal total = stageDeals.stream()
                    .map(Deal::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                return new ColumnResponse(
                    toStageResponse(stage),
                    stageDeals.stream().map(d -> DealResponse.from(d, leadNames.get(d.getLeadId()))).toList(),
                    total);
            }).toList();
        
        return new BoardResponse(pipeline.getId(), pipeline.getName(), columns);
    }

    @Transactional
    public DealResponse moveDeal(UUID dealId, UUID toStageId, UUID movedBy) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Deal deal = dealRepo.findByIdAndTenantId(dealId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Deal", dealId));
        
        UUID fromStageId = deal.getStage().getId();
        long timeInStage = Instant.now().getEpochSecond() - deal.getEnteredStageAt().getEpochSecond();
        
        PipelineStage newStage = deal.getPipeline().getStages().stream()
            .filter(s -> s.getId().equals(toStageId))
            .findFirst()
            .orElseThrow(() -> BusinessException.notFound("Stage", toStageId));
        
        historyRepo.save(DealStageHistory.builder()
            .dealId(dealId).tenantId(tenantId)
            .fromStageId(fromStageId).toStageId(toStageId)
            .movedBy(movedBy).timeInStage((int) timeInStage)
            .build());
        
        deal.setStage(newStage);
        deal.setEnteredStageAt(Instant.now());
        
        return DealResponse.from(dealRepo.save(deal));
    }

    @Transactional
    public PipelineResponse createPipeline(CreatePipelineRequest req, UUID createdBy) {
        UUID tenantId = UUID.fromString(TenantContext.get());

        boolean makeDefault = Boolean.TRUE.equals(req.isDefault());
        // se for default, desmarca o anterior; se for o 1º pipeline do tenant, force default
        if (makeDefault) {
            pipelineRepo.findByTenantIdAndIsDefaultTrue(tenantId).ifPresent(prev -> {
                prev.setIsDefault(false);
                pipelineRepo.save(prev);
            });
        } else if (pipelineRepo.findByTenantIdAndActiveOrderByIsDefaultDesc(tenantId, true).isEmpty()) {
            makeDefault = true;
        }

        Pipeline pipeline = Pipeline.builder()
            .tenantId(tenantId)
            .name(req.name())
            .description(req.description())
            .isDefault(makeDefault)
            .active(true)
            .createdBy(createdBy)
            .build();

        return toPipelineResponse(pipelineRepo.save(pipeline));
    }

    // ── Stages CRUD ──────────────────────────────────────────

    @Transactional
    public StageResponse createStage(UUID pipelineId, CreateStageRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Pipeline pipeline = pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Pipeline", pipelineId));

        if (req.name() == null || req.name().isBlank()) {
            throw BusinessException.validation("Stage name is required");
        }
        short nextOrder = (short) pipeline.getStages().stream()
            .mapToInt(s -> s.getOrderIndex() == null ? 0 : s.getOrderIndex() + 1)
            .max().orElse(0);

        PipelineStage stage = PipelineStage.builder()
            .pipeline(pipeline)
            .tenantId(tenantId)
            .name(req.name().trim())
            .orderIndex(nextOrder)
            .color(req.color() != null && !req.color().isBlank() ? req.color() : "#5e6ad2")
            .slaHours(req.slaHours())
            .isWon(Boolean.TRUE.equals(req.isWon()))
            .isLost(Boolean.TRUE.equals(req.isLost()))
            .build();
        pipeline.getStages().add(stage);
        pipelineRepo.save(pipeline);
        return toStageResponse(stage);
    }

    @Transactional
    public StageResponse updateStage(UUID pipelineId, UUID stageId, UpdateStageRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Pipeline pipeline = pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Pipeline", pipelineId));
        PipelineStage stage = pipeline.getStages().stream()
            .filter(s -> s.getId().equals(stageId))
            .findFirst()
            .orElseThrow(() -> BusinessException.notFound("Stage", stageId));

        if (req.name() != null && !req.name().isBlank()) stage.setName(req.name().trim());
        if (req.color() != null && !req.color().isBlank()) stage.setColor(req.color());
        if (req.slaHours() != null) stage.setSlaHours(req.slaHours());
        if (req.orderIndex() != null) stage.setOrderIndex(req.orderIndex());
        if (req.isWon() != null) stage.setIsWon(req.isWon());
        if (req.isLost() != null) stage.setIsLost(req.isLost());

        pipelineRepo.save(pipeline);
        return toStageResponse(stage);
    }

    @Transactional
    public List<StageResponse> reorderStages(UUID pipelineId, List<UUID> orderedStageIds) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Pipeline pipeline = pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Pipeline", pipelineId));

        Map<UUID, PipelineStage> byId = pipeline.getStages().stream()
            .collect(Collectors.toMap(PipelineStage::getId, s -> s));

        // Aplica novo orderIndex pelos IDs informados; stages ausentes na lista vão pro fim.
        short idx = 0;
        for (UUID sid : orderedStageIds) {
            PipelineStage s = byId.get(sid);
            if (s != null) {
                s.setOrderIndex(idx++);
            }
        }
        List<PipelineStage> leftovers = pipeline.getStages().stream()
            .filter(s -> !orderedStageIds.contains(s.getId()))
            .sorted(Comparator.comparing(PipelineStage::getOrderIndex))
            .toList();
        for (PipelineStage s : leftovers) {
            s.setOrderIndex(idx++);
        }

        pipelineRepo.save(pipeline);
        return pipeline.getStages().stream()
            .sorted(Comparator.comparing(PipelineStage::getOrderIndex))
            .map(this::toStageResponse)
            .toList();
    }

    @Transactional
    public void deleteStage(UUID pipelineId, UUID stageId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Pipeline pipeline = pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Pipeline", pipelineId));
        PipelineStage stage = pipeline.getStages().stream()
            .filter(s -> s.getId().equals(stageId))
            .findFirst()
            .orElseThrow(() -> BusinessException.notFound("Stage", stageId));

        // Bloqueia exclusão se houver negócios na etapa
        long openInStage = dealRepo.findBoardDeals(pipelineId, tenantId).stream()
            .filter(d -> stageId.equals(d.getStage().getId()))
            .count();
        if (openInStage > 0) {
            throw BusinessException.validation("Não é possível excluir uma etapa que possui negócios. Mova-os primeiro.");
        }
        pipeline.getStages().remove(stage);
        pipelineRepo.save(pipeline);
    }

    @Transactional
    public DealResponse createDeal(CreateDealRequest req, UUID createdBy) {
        UUID tenantId = UUID.fromString(TenantContext.get());

        // Localiza a stage em qualquer pipeline ativa do tenant — não restringe à default.
        Pipeline pipeline = null;
        PipelineStage stage = null;
        for (Pipeline p : pipelineRepo.findByTenantIdAndActiveOrderByIsDefaultDesc(tenantId, true)) {
            for (PipelineStage s : p.getStages()) {
                if (s.getId().equals(req.stageId())) {
                    pipeline = p;
                    stage = s;
                    break;
                }
            }
            if (stage != null) break;
        }
        if (stage == null) {
            throw BusinessException.notFound("Stage", req.stageId());
        }
        
        Deal deal = Deal.builder()
            .tenantId(tenantId).leadId(req.leadId())
            .pipeline(pipeline).stage(stage)
            .title(req.title())
            .value(req.value() != null ? req.value() : BigDecimal.ZERO)
            .assignedTo(req.assignedTo())
            .expectedCloseAt(req.expectedCloseAt())
            .createdBy(createdBy)
            .build();
        
        return DealResponse.from(dealRepo.save(deal));
    }

    private PipelineResponse toPipelineResponse(Pipeline p) {
        return new PipelineResponse(p.getId(), p.getName(), p.getDescription(), p.getIsDefault(), 
            p.getStages().stream().map(this::toStageResponse).toList());
    }

    private StageResponse toStageResponse(PipelineStage s) {
        return new StageResponse(s.getId(), s.getName(), s.getOrderIndex(), s.getColor(), s.getSlaHours(), s.getIsWon(), s.getIsLost());
    }
}
