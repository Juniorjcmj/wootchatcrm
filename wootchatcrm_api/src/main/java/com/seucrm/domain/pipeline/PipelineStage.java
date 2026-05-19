package com.seucrm.domain.pipeline;

import com.seucrm.shared.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "pipeline_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineStage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Short orderIndex = 0;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#5e6ad2";

    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "is_won", nullable = false)
    @Builder.Default
    private Boolean isWon = false;

    @Column(name = "is_lost", nullable = false)
    @Builder.Default
    private Boolean isLost = false;
}
