package com.seucrm.domain.conversation;

import com.seucrm.shared.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, length = 20, columnDefinition = "connection_provider")
    private ConnectionProvider provider;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "credentials_enc", columnDefinition = "TEXT")
    private String credentialsEnc;

    @Column(name = "webhook_token", length = 255)
    private String webhookToken;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean connected = false;

    @Column(name = "last_connected_at")
    private Instant lastConnectedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    public enum ConnectionProvider {
        ZAPI,
        WAHA,
        META_BSP,
        EVOLUTION
    }
}
