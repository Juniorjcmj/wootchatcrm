package com.seucrm.domain.automation;

import com.seucrm.shared.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "automations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Automation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private TriggerType triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_config", nullable = false)
    @Builder.Default
    private Map<String, Object> triggerConfig = new java.util.HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AutomationStatus status = AutomationStatus.DRAFT;

    @Column(name = "run_count", nullable = false)
    @Builder.Default
    private Integer runCount = 0;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @OneToMany(mappedBy = "automation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<AutomationAction> actions = new ArrayList<>();

    @OneToMany(mappedBy = "automation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AutomationCondition> conditions = new ArrayList<>();

    public enum AutomationStatus {
        ACTIVE,
        PAUSED,
        DRAFT
    }

    public enum TriggerType {
        STAGE_ENTERED,
        STAGE_LEFT,
        DEAL_CREATED,
        DEAL_WON,
        DEAL_LOST,
        MESSAGE_RECEIVED,
        NO_REPLY_TIMEOUT,
        TAG_ADDED,
        LEAD_CREATED,
        LEAD_ASSIGNED,
        WEBHOOK_RECEIVED
    }
}
