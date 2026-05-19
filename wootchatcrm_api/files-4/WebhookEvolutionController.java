package com.seucrm.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.seucrm.api.connection.EvolutionConnectionService;
import com.seucrm.domain.conversation.*;
import com.seucrm.domain.lead.Lead;
import com.seucrm.domain.lead.LeadChannel;
import com.seucrm.domain.lead.LeadRepository;
import com.seucrm.integration.whatsapp.InboundMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/webhooks/evolution")
@RequiredArgsConstructor
public class WebhookEvolutionController {

    private final WebhookDispatcher            dispatcher;
    private final WhatsAppConnectionRepository connectionRepo;
    private final MessageRepository            messageRepo;
    private final EvolutionConnectionService   evolutionConnectionService;
    private final SimpMessagingTemplate        messagingTemplate;

    // ── Endpoint principal: recebe todos os eventos ──────────
    // URL registrada no Evolution Go:
    // https://api.seucrm.com.br/api/v1/webhooks/evolution/{connectionId}

    @PostMapping("/{connectionId}")
    @Transactional
    public ResponseEntity<Void> handle(
            @PathVariable UUID connectionId,
            @RequestBody JsonNode payload) {

        String event    = payload.path("event").asText("");
        String instance = payload.path("instance").asText("");

        log.debug("[WEBHOOK-EVOLUTION] evento={} instancia={} conexao={}",
                event, instance, connectionId);

        switch (event) {
            case "messages.upsert"   -> processMessageUpsert(connectionId, payload);
            case "messages.update"   -> processMessagesUpdate(connectionId, payload);
            case "connection.update" -> processConnectionUpdate(connectionId, payload);
            case "qrcode.updated"    -> processQrCodeUpdate(connectionId, payload);
            default -> log.debug("[WEBHOOK-EVOLUTION] Evento ignorado: {}", event);
        }

        // Evolution Go espera 200 imediatamente — processamento é assíncrono
        return ResponseEntity.ok().build();
    }

    // ── MESSAGES_UPSERT — nova mensagem ──────────────────────

    private void processMessageUpsert(UUID connectionId, JsonNode payload) {
        JsonNode data    = payload.path("data");
        JsonNode key     = data.path("key");
        boolean  fromMe  = key.path("fromMe").asBoolean();

        // Ignorar mensagens enviadas pelo CRM (eco) — evita loop infinito
        if (fromMe) {
            log.debug("[WEBHOOK-EVOLUTION] Mensagem fromMe ignorada");
            return;
        }

        String remoteJid    = key.path("remoteJid").asText("");
        String messageId    = key.path("id").asText("");

        // Ignorar mensagens de grupo (@g.us) — fora do escopo do CRM por enquanto
        if (remoteJid.endsWith("@g.us")) {
            log.debug("[WEBHOOK-EVOLUTION] Mensagem de grupo ignorada: {}", remoteJid);
            return;
        }

        // Extrair número limpo: "5521987654321@s.whatsapp.net" → "5521987654321"
        String phone     = remoteJid
                .replace("@s.whatsapp.net", "")
                .replace("@c.us", "")
                .replaceAll("[^0-9]", "");

        String pushName     = data.path("pushName").asText(null);
        String messageType  = data.path("messageType").asText("conversation");
        JsonNode message    = data.path("message");

        String content  = extractContent(message, messageType);
        String mediaUrl = extractMediaUrl(message, messageType);
        String mediaMime = extractMediMime(message, messageType);

        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Conexão não encontrada: " + connectionId));

        dispatcher.dispatch(InboundMessageEvent.builder()
                .connectionId(connectionId)
                .tenantId(conn.getTenantId())
                .externalChatId(remoteJid)
                .senderPhone(phone)
                .senderName(pushName)
                .externalMessageId(messageId)
                .type(mapMessageType(messageType))
                .content(content)
                .mediaUrl(mediaUrl)
                .mediaMime(mediaMime)
                .receivedAt(Instant.now())
                .build());
    }

    // ── MESSAGES_UPDATE — atualização de status de entrega ───

    private void processMessagesUpdate(UUID connectionId, JsonNode payload) {
        JsonNode dataArray = payload.path("data");
        if (!dataArray.isArray()) return;

        WhatsAppConnection conn = connectionRepo.findById(connectionId).orElse(null);
        if (conn == null) return;

        dataArray.forEach(update -> {
            String msgId = update.path("key").path("id").asText("");
            if (msgId.isBlank()) return;

            int statusCode = update.path("update").path("status").asInt(0);
            Message.MessageStatus newStatus = mapDeliveryStatus(statusCode);

            messageRepo.findByTenantIdAndExternalId(conn.getTenantId(), msgId)
                    .ifPresent(msg -> {
                        msg.setStatus(newStatus);
                        if (newStatus == Message.MessageStatus.DELIVERED)
                            msg.setDeliveredAt(Instant.now());
                        if (newStatus == Message.MessageStatus.READ)
                            msg.setReadAt(Instant.now());
                        messageRepo.save(msg);

                        // Broadcast WebSocket → atualiza ✓✓ no ChatPanel
                        messagingTemplate.convertAndSend(
                                "/topic/conversation/" + msg.getConversationId(),
                                Map.of(
                                        "type",       "STATUS_UPDATE",
                                        "messageId",  msg.getId().toString(),
                                        "externalId", msgId,
                                        "status",     newStatus.name()
                                )
                        );

                        log.debug("[WEBHOOK-EVOLUTION] Status atualizado: msgId={} → {}",
                                msgId, newStatus);
                    });
        });
    }

    // ── CONNECTION_UPDATE — mudança de estado da conexão ─────

    private void processConnectionUpdate(UUID connectionId, JsonNode payload) {
        String state = payload.path("data").path("state").asText("close");
        evolutionConnectionService.handleConnectionUpdate(connectionId, state);
    }

    // ── QRCODE_UPDATED — novo QR Code disponível ─────────────

    private void processQrCodeUpdate(UUID connectionId, JsonNode payload) {
        String base64 = payload.path("data").path("qrcode").path("base64").asText(null);
        if (base64 != null) {
            evolutionConnectionService.handleQrCodeUpdate(connectionId, base64);
        }
    }

    // ── Utilitários de extração ──────────────────────────────

    private String extractContent(JsonNode message, String type) {
        return switch (type) {
            case "conversation"         -> message.path("conversation").asText(null);
            case "extendedTextMessage"  -> message.path("extendedTextMessage")
                                                   .path("text").asText(null);
            case "imageMessage"         -> message.path("imageMessage")
                                                   .path("caption").asText(null);
            case "videoMessage"         -> message.path("videoMessage")
                                                   .path("caption").asText(null);
            case "documentMessage"      -> message.path("documentMessage")
                                                   .path("fileName").asText(null);
            case "audioMessage", "pttMessage" -> null; // áudio não tem texto
            default                     -> null;
        };
    }

    private String extractMediaUrl(JsonNode message, String type) {
        return switch (type) {
            case "imageMessage"    -> message.path("imageMessage").path("url").asText(null);
            case "videoMessage"    -> message.path("videoMessage").path("url").asText(null);
            case "audioMessage",
                 "pttMessage"      -> message.path("audioMessage").path("url").asText(null);
            case "documentMessage" -> message.path("documentMessage").path("url").asText(null);
            case "stickerMessage"  -> message.path("stickerMessage").path("url").asText(null);
            default                -> null;
        };
    }

    private String extractMediMime(JsonNode message, String type) {
        return switch (type) {
            case "imageMessage"    -> message.path("imageMessage").path("mimetype").asText(null);
            case "videoMessage"    -> message.path("videoMessage").path("mimetype").asText(null);
            case "audioMessage",
                 "pttMessage"      -> message.path("audioMessage").path("mimetype").asText("audio/ogg");
            case "documentMessage" -> message.path("documentMessage").path("mimetype").asText(null);
            default                -> null;
        };
    }

    private Message.MessageType mapMessageType(String type) {
        return switch (type) {
            case "imageMessage"              -> Message.MessageType.IMAGE;
            case "videoMessage"              -> Message.MessageType.VIDEO;
            case "audioMessage", "pttMessage" -> Message.MessageType.AUDIO;
            case "documentMessage"           -> Message.MessageType.DOCUMENT;
            case "stickerMessage"            -> Message.MessageType.STICKER;
            case "locationMessage"           -> Message.MessageType.LOCATION;
            case "contactMessage"            -> Message.MessageType.CONTACT;
            default                          -> Message.MessageType.TEXT;
        };
    }

    private Message.MessageStatus mapDeliveryStatus(int code) {
        return switch (code) {
            case 1  -> Message.MessageStatus.PENDING;
            case 2  -> Message.MessageStatus.SENT;
            case 3  -> Message.MessageStatus.DELIVERED;
            case 4  -> Message.MessageStatus.READ;
            default -> Message.MessageStatus.SENT;
        };
    }
}
