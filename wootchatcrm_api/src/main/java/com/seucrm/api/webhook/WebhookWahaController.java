package com.seucrm.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.seucrm.api.connection.WahaConnectionService;
import com.seucrm.domain.conversation.Message;
import com.seucrm.domain.conversation.WhatsAppConnection;
import com.seucrm.domain.conversation.WhatsAppConnectionRepository;
import com.seucrm.integration.whatsapp.InboundMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Webhook receiver para o WAHA.
 *
 * URL: {WEBHOOK_BASE_URL}/v1/webhooks/waha/{connectionId}
 *
 * Eventos relevantes:
 *   - "session.status"  → mudança de estado da sessão (STOPPED/STARTING/SCAN_QR_CODE/WORKING/FAILED)
 *   - "message"         → mensagem inbound do contato
 *   - "message.any"     → inclui mensagens enviadas (não usamos por padrão)
 */
@Slf4j
@RestController
@RequestMapping("/v1/webhooks/waha")
@RequiredArgsConstructor
public class WebhookWahaController {

    private final WebhookDispatcher            dispatcher;
    private final WhatsAppConnectionRepository connectionRepo;
    private final WahaConnectionService        wahaConnectionService;

    @PostMapping("/{connectionId}")
    @Transactional
    public ResponseEntity<Void> handle(
            @PathVariable UUID connectionId,
            @RequestBody JsonNode payload) {

        String event = payload.path("event").asText("");

        String dump = payload.toString();
        log.info("[WEBHOOK-WAHA] event={} conexao={} payload={}",
                event, connectionId,
                dump.length() > 4000 ? dump.substring(0, 4000) + "…(truncado)" : dump);

        switch (event) {
            case "session.status" -> processSessionStatus(connectionId, payload);
            case "message"        -> processMessage(connectionId, payload);
            default               -> log.debug("[WEBHOOK-WAHA] Evento ignorado: {}", event);
        }

        return ResponseEntity.ok().build();
    }

    // ── session.status ──────────────────────────────────────
    // payload.payload.status: STOPPED|STARTING|SCAN_QR_CODE|WORKING|FAILED
    // payload.payload.qr:    presente quando status=SCAN_QR_CODE (base64)
    private void processSessionStatus(UUID connectionId, JsonNode payload) {
        JsonNode p = payload.path("payload");
        String status = p.path("status").asText("");
        String qr     = p.path("qr").asText(null);

        if (qr != null && !qr.isBlank()) {
            // WAHA pode mandar com prefixo data: ou só base64 puro
            String b64 = qr.startsWith("data:") ? qr : ("data:image/png;base64," + qr);
            wahaConnectionService.handleQrCodeUpdate(connectionId, b64);
        }

        wahaConnectionService.handleConnectionUpdate(connectionId, status);
    }

    // ── message ─────────────────────────────────────────────
    // payload.payload: { id, from, to, body, fromMe, hasMedia, mediaUrl, mimetype, ... }
    private void processMessage(UUID connectionId, JsonNode payload) {
        JsonNode p = payload.path("payload");

        boolean fromMe  = p.path("fromMe").asBoolean(false);
        String chatId   = p.path("from").asText("");     // ex: 5511999999999@c.us
        String sender   = fromMe ? p.path("to").asText(chatId) : chatId;
        String msgId    = p.path("id").asText("");
        String body     = p.path("body").asText("");
        boolean isGroup = chatId.endsWith("@g.us");

        // WAHA tem hasMedia + mediaUrl + mimetype
        boolean hasMedia = p.path("hasMedia").asBoolean(false);
        String mediaUrl  = p.path("mediaUrl").asText(null);
        String mime      = p.path("mimetype").asText(null);

        WhatsAppConnection conn = connectionRepo.findById(connectionId).orElse(null);
        if (conn == null) {
            log.warn("[WEBHOOK-WAHA] Conexão {} não encontrada — ignorando mensagem", connectionId);
            return;
        }

        Message.MessageType type;
        if (hasMedia) {
            if (mime != null && mime.startsWith("image/"))      type = Message.MessageType.IMAGE;
            else if (mime != null && mime.startsWith("video/")) type = Message.MessageType.VIDEO;
            else if (mime != null && mime.startsWith("audio/")) type = Message.MessageType.AUDIO;
            else                                                 type = Message.MessageType.DOCUMENT;
        } else {
            type = Message.MessageType.TEXT;
        }

        String phoneDigits = sender.split("@")[0].replaceAll("[^0-9]", "");

        dispatcher.dispatch(InboundMessageEvent.builder()
                .connectionId(connectionId)
                .tenantId(conn.getTenantId())
                .externalChatId(chatId)
                .senderPhone(phoneDigits)
                .senderName(p.path("notifyName").asText(p.path("pushName").asText("")))
                .externalMessageId(msgId)
                .type(type)
                .content(body)
                .mediaUrl(mediaUrl)
                .mediaMime(mime)
                .receivedAt(Instant.now())
                .fromMe(fromMe)
                .isGroup(isGroup)
                .groupName(isGroup ? p.path("chatName").asText("") : null)
                .build());
    }
}
