package com.seucrm.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.seucrm.domain.conversation.*;
import com.seucrm.integration.whatsapp.InboundMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookDispatcher dispatcher;
    private final WhatsAppConnectionRepository connectionRepo;

    @PostMapping("/zapi/{connectionId}")
    public ResponseEntity<Void> zapiWebhook(@PathVariable UUID connectionId, @RequestBody JsonNode payload) {
        log.debug("[Z-API] Webhook received for connection {}", connectionId);
        try {
            String type = payload.path("type").asText();
            if ("ReceivedCallback".equals(type) || payload.has("text")) {
                WhatsAppConnection conn = connectionRepo.findById(connectionId).orElseThrow();
                String phone = payload.path("phone").asText().replaceAll("[^0-9]", "");
                String msgId = payload.path("messageId").asText();
                String content = payload.path("text").path("message").asText();
                
                dispatcher.dispatch(InboundMessageEvent.builder()
                    .connectionId(connectionId)
                    .tenantId(conn.getTenantId())
                    .externalChatId(phone + "@c.us")
                    .senderPhone(phone)
                    .senderName(payload.path("senderName").asText(null))
                    .externalMessageId(msgId)
                    .type(Message.MessageType.TEXT)
                    .content(content)
                    .receivedAt(Instant.now())
                    .build());
            }
        } catch (Exception e) {
            log.error("[Z-API] Processing error: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    // O webhook do WAHA agora é tratado por WebhookWahaController
    // (POST /v1/webhooks/waha/{connectionId}) que cobre session.status,
    // mensagens e mídias com o shape correto.

    @GetMapping("/meta/{connectionId}")
    public ResponseEntity<String> metaVerify(
        @PathVariable UUID connectionId,
        @RequestParam("hub.mode") String mode,
        @RequestParam("hub.verify_token") String token,
        @RequestParam("hub.challenge") String challenge
    ) {
        return ResponseEntity.ok(challenge);
    }

    @PostMapping("/meta/{connectionId}")
    public ResponseEntity<Void> metaWebhook(@PathVariable UUID connectionId, @RequestBody JsonNode payload) {
        log.debug("[META_BSP] Webhook received for connection {}", connectionId);
        try {
            WhatsAppConnection conn = connectionRepo.findById(connectionId).orElseThrow();
            JsonNode changes = payload.path("entry").path(0).path("changes").path(0).path("value");
            JsonNode messages = changes.path("messages");
            
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    String from = msg.path("from").asText();
                    String msgId = msg.path("id").asText();
                    String typeStr = msg.path("type").asText();
                    String content = "text".equals(typeStr) ? msg.path("text").path("body").asText() : null;
                    
                    String senderName = null;
                    JsonNode contacts = changes.path("contacts");
                    if (contacts.isArray() && !contacts.isEmpty()) {
                        senderName = contacts.path(0).path("profile").path("name").asText(null);
                    }

                    dispatcher.dispatch(InboundMessageEvent.builder()
                        .connectionId(connectionId)
                        .tenantId(conn.getTenantId())
                        .externalChatId(from)
                        .senderPhone(from)
                        .senderName(senderName)
                        .externalMessageId(msgId)
                        .type(detectType(typeStr))
                        .content(content)
                        .receivedAt(Instant.now())
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("[META_BSP] Processing error: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    // POST /v1/webhooks/evolution/{connectionId} é tratado pelo
    // WebhookEvolutionController dedicado (api.webhook.WebhookEvolutionController),
    // que implementa o pipeline completo: messages.upsert, messages.update,
    // connection.update, qrcode.updated.

    private Message.MessageType detectType(String raw) {
        if (raw == null) return Message.MessageType.TEXT;
        return switch (raw.toLowerCase()) {
            case "audio", "audiomessage", "ptt" -> Message.MessageType.AUDIO;
            case "image", "imagemessage" -> Message.MessageType.IMAGE;
            case "video", "videomessage" -> Message.MessageType.VIDEO;
            case "document", "documentmessage" -> Message.MessageType.DOCUMENT;
            case "sticker", "stickermessage" -> Message.MessageType.STICKER;
            case "location", "locationmessage" -> Message.MessageType.LOCATION;
            default -> Message.MessageType.TEXT;
        };
    }
}
