package com.seucrm.integration.whatsapp;

import com.seucrm.domain.conversation.Message;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record InboundMessageEvent(
    UUID connectionId,
    UUID tenantId,
    String externalChatId,
    String senderPhone,
    String senderName,
    String externalMessageId,
    Message.MessageType type,
    String content,
    String mediaUrl,
    String mediaMime,
    Long mediaSizeBytes,
    Integer mediaDurationS,
    String mediaBase64,        // payload decriptado entregue inline pelo Evolution Go (PTT, image, ...)
    String quotedExternalId,
    Instant receivedAt,
    boolean fromMe,            // true = mensagem saiu da própria instância (agente). False = inbound de contato.
    boolean isGroup,           // chat em grupo (@g.us)
    String  groupName          // nome do grupo (quando isGroup=true)
) {}
