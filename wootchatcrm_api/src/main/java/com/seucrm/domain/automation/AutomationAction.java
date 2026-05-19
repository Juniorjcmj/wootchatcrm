package com.seucrm.domain.automation;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "automation_actions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_id", nullable = false)
    private Automation automation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActionType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @Builder.Default
    private Map<String, Object> config = new java.util.HashMap<>();

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Short orderIndex = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum ActionType {
        SEND_WHATSAPP_TEXT,
        SEND_WHATSAPP_TEMPLATE,
        MOVE_DEAL_STAGE,
        ASSIGN_AGENT,
        ADD_TAG,
        REMOVE_TAG,
        CREATE_ACTIVITY,
        SEND_WEBHOOK,
        WAIT,
        FINISH_CONVERSATION
    }
}
