package com.seucrm.domain.tenant;

import com.seucrm.shared.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PlanType plan = PlanType.STARTER;

    @Column(name = "plan_expires_at")
    private Instant planExpiresAt;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String timezone = "America/Sao_Paulo";

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String locale = "pt-BR";

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    public enum PlanType {
        STARTER,
        ESSENTIAL,
        PRO
    }
}
