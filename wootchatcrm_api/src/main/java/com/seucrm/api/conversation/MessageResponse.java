package com.seucrm.api.conversation;

import com.seucrm.domain.conversation.Message;

import java.time.Instant;
import java.util.UUID;

/**
 * Shape público de uma mensagem (REST + WebSocket).
 * NÃO inclui media_base64 — esse blob é servido sob demanda via GET /v1/messages/{id}/media.
 */
public record MessageResponse(
        UUID id,
        String direction,
        String senderType,
        UUID senderId,
        String type,
        String content,
        String mediaUrl,
        String mediaMime,
        Integer mediaDurationS,
        boolean hasMedia,
        String status,
        Instant createdAt
) {
    public static MessageResponse from(Message m) {
        boolean hasMedia = m.getMediaBase64() != null && !m.getMediaBase64().isBlank();
        return new MessageResponse(
                m.getId(),
                m.getDirection().name(),
                m.getSenderType().name(),
                m.getSenderId(),
                m.getType().name(),
                m.getContent(),
                m.getMediaUrl(),
                m.getMediaMime(),
                m.getMediaDurationS(),
                hasMedia,
                m.getStatus().name(),
                m.getCreatedAt()
        );
    }
}
