package com.seucrm.api.automation;

import com.seucrm.config.TenantContext;
import com.seucrm.domain.automation.*;
import com.seucrm.domain.user.User;
import com.seucrm.shared.exception.BusinessException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record CreateAutomationRequest(
    @NotBlank String name,
    String description,
    @NotNull Automation.TriggerType triggerType,
    Map<String, Object> triggerConfig,
    List<ConditionRequest> conditions,
    List<ActionRequest> actions
) {}

record ConditionRequest(String field, String operator, String value) {}
record ActionRequest(AutomationAction.ActionType type, Map<String, Object> config) {}

record AutomationResponse(
    UUID id,
    String name,
    String description,
    String triggerType,
    String status,
    int runCount,
    Instant lastRunAt
) {
    static AutomationResponse from(Automation a) {
        return new AutomationResponse(
            a.getId(),
            a.getName(),
            a.getDescription(),
            a.getTriggerType().name(),
            a.getStatus().name(),
            a.getRunCount(),
            a.getLastRunAt()
        );
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class AutomationService {

    private final AutomationRepository automationRepo;

    @Transactional(readOnly = true)
    public List<AutomationResponse> list() {
        UUID tenantId = UUID.fromString(TenantContext.get());
        return automationRepo.findByTenantIdAndStatus(tenantId, Automation.AutomationStatus.ACTIVE)
            .stream().map(AutomationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AutomationResponse> listAll() {
        UUID tenantId = UUID.fromString(TenantContext.get());
        return automationRepo.findAll().stream()
            .filter(a -> a.getTenantId().equals(tenantId))
            .map(AutomationResponse::from)
            .toList();
    }

    @Transactional
    public AutomationResponse create(CreateAutomationRequest req, UUID createdBy) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        
        Automation automation = Automation.builder()
            .tenantId(tenantId)
            .name(req.name())
            .description(req.description())
            .triggerType(req.triggerType())
            .triggerConfig(req.triggerConfig() != null ? req.triggerConfig() : Map.of())
            .status(Automation.AutomationStatus.DRAFT)
            .createdBy(createdBy)
            .build();

        if (req.conditions() != null) {
            req.conditions().forEach(c -> {
                AutomationCondition condition = AutomationCondition.builder()
                    .automation(automation)
                    .field(c.field())
                    .operator(c.operator())
                    .value(c.value())
                    .build();
                automation.getConditions().add(condition);
            });
        }

        if (req.actions() != null) {
            for (int i = 0; i < req.actions().size(); i++) {
                ActionRequest ar = req.actions().get(i);
                AutomationAction action = AutomationAction.builder()
                    .automation(automation)
                    .type(ar.type())
                    .config(ar.config() != null ? ar.config() : Map.of())
                    .orderIndex((short) i)
                    .build();
                automation.getActions().add(action);
            }
        }

        return AutomationResponse.from(automationRepo.save(automation));
    }

    @Transactional
    public AutomationResponse toggle(UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Automation automation = automationRepo.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Automation", id));
        
        Automation.AutomationStatus next = automation.getStatus() == Automation.AutomationStatus.ACTIVE 
            ? Automation.AutomationStatus.PAUSED 
            : Automation.AutomationStatus.ACTIVE;
        
        automation.setStatus(next);
        return AutomationResponse.from(automationRepo.save(automation));
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Automation automation = automationRepo.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Automation", id));
        automationRepo.delete(automation);
    }

    @Transactional
    public void trigger(Automation.TriggerType triggerType, UUID tenantId, UUID leadId, Map<String, Object> context) {
        List<Automation> automations = automationRepo
            .findByTenantIdAndTriggerTypeAndStatus(tenantId, triggerType, Automation.AutomationStatus.ACTIVE);
        
        for (Automation automation : automations) {
            try {
                if (matchesConditions(automation, context)) {
                    executeActions(automation, leadId, context);
                    automation.setRunCount(automation.getRunCount() + 1);
                    automation.setLastRunAt(Instant.now());
                    automationRepo.save(automation);
                }
            } catch (Exception e) {
                log.error("Automation {} failed for lead {}: {}", automation.getId(), leadId, e.getMessage());
            }
        }
    }

    private boolean matchesConditions(Automation automation, Map<String, Object> context) {
        return automation.getConditions().stream().allMatch(condition -> {
            Object val = context.get(condition.getField());
            if (val == null) return false;
            return switch (condition.getOperator()) {
                case "eq" -> val.toString().equals(condition.getValue());
                case "neq" -> !val.toString().equals(condition.getValue());
                case "contains" -> val.toString().contains(condition.getValue());
                default -> true;
            };
        });
    }

    private void executeActions(Automation automation, UUID leadId, Map<String, Object> context) {
        for (AutomationAction action : automation.getActions()) {
            log.info("Executing action {} for automation {} lead {}", action.getType(), automation.getId(), leadId);
        }
    }
}
