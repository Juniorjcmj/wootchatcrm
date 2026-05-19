package com.seucrm.domain.conversation;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "message_direction")
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "sender_type", nullable = false, columnDefinition = "message_sender_type")
    private MessageSenderType senderType;

    @Column(name = "sender_id")
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "message_type")
    @Builder.Default
    private MessageType type = MessageType.TEXT;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "media_url", length = 1000)
    private String mediaUrl;

    @Column(name = "media_mime", length = 100)
    private String mediaMime;

    @Column(name = "media_size_bytes")
    private Long mediaSizeBytes;

    @Column(name = "media_duration_s")
    private Integer mediaDurationS;

    // Base64 do áudio/imagem/documento decriptado (entregue no webhook por WEBHOOK_FILES=true).
    // Servido sob demanda em GET /v1/messages/{id}/media — não exposto na listagem.
    @Column(name = "media_base64", columnDefinition = "TEXT")
    private String mediaBase64;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "quoted_message_id")
    private UUID quotedMessageId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "message_status")
    @Builder.Default
    private MessageStatus status = MessageStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum MessageDirection {
        INBOUND,
        OUTBOUND
    }

    public enum MessageSenderType {
        LEAD,
        AGENT,
        BOT,
        SYSTEM
    }

    public enum MessageType {
        TEXT,
        AUDIO,
        IMAGE,
        VIDEO,
        DOCUMENT,
        STICKER,
        LOCATION,
        CONTACT,
        TEMPLATE,
        INTERACTIVE,
        SYSTEM
    }

    public enum MessageStatus {
        PENDING,
        SENT,
        DELIVERED,
        READ,
        FAILED
    }
}
