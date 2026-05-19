package com.seucrm.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.seucrm.api.connection.WppConnectConnectionService;
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
 * Webhook receiver para o WPPConnect Server.
 *
 * URL: {WEBHOOK_BASE_URL}/v1/webhooks/wppconnect/{connectionId}
 *
 * O WPPConnect envia diferentes eventos no body. Os mais relevantes:
 *   - event="qrcode"     → { event:"qrcode", session, data:"<base64>" }  (ou em base64Qrimg)
 *   - event="status-find"→ { event:"status-find", session, status:"qrReadSuccess"|"inChat"|"isLogout"|... }
 *   - event="onmessage"  → { event:"onmessage", session, from, to, body, type, isGroupMsg, fromMe, … }
 *   - event="onstatechange" → { event:"onstatechange", state:"CONNECTED"|"DISCONNECTED"|... }
 *
 * Algumas versões mandam direto o payload sem `event` — usamos best-effort
 * detection pelos campos presentes.
 */
@Slf4j
@RestController
@RequestMapping("/v1/webhooks/wppconnect")
@RequiredArgsConstructor
public class WebhookWppConnectController {

    private final WebhookDispatcher              dispatcher;
    private final WhatsAppConnectionRepository   connectionRepo;
    private final WppConnectConnectionService    wppService;

    @PostMapping("/{connectionId}")
    @Transactional
    public ResponseEntity<Void> handle(
            @PathVariable UUID connectionId,
            @RequestBody JsonNode payload) {

        String event = payload.path("event").asText("");

        String dump = payload.toString();
        log.info("[WEBHOOK-WPP] event={} conexao={} payload={}",
                event, connectionId,
                dump.length() > 4000 ? dump.substring(0, 4000) + "…(truncado)" : dump);

        switch (event) {
            case "qrcode"          -> processQrCode(connectionId, payload);
            case "status-find"     -> processStatusFind(connectionId, payload);
            case "onstatechange"   -> processStateChange(connectionId, payload);
            case "onmessage"       -> processMessage(connectionId, payload);
            case ""                -> tryAutoDetect(connectionId, payload);
            default                -> log.debug("[WEBHOOK-WPP] Evento ignorado: {}", event);
        }

        return ResponseEntity.ok().build();
    }

    // QR pode vir em `data`, `base64Qrimg` ou `qrcode` dependendo da versão.
    private void processQrCode(UUID connectionId, JsonNode payload) {
        String b64 = firstNonBlank(
                payload.path("data").asText(null),
                payload.path("base64Qrimg").asText(null),
                payload.path("qrcode").asText(null),
                payload.path("base64").asText(null)
        );
        if (b64 == null) return;
        String full = b64.startsWith("data:") ? b64 : ("data:image/png;base64," + b64);
        wppService.handleQrCodeUpdate(connectionId, full);
    }

    private void processStatusFind(UUID connectionId, JsonNode payload) {
        String status = payload.path("status").asText("");
        wppService.handleConnectionUpdate(connectionId, status);
    }

    private void processStateChange(UUID connectionId, JsonNode payload) {
        String state = payload.path("state").asText("");
        wppService.handleConnectionUpdate(connectionId, state);
    }

    private void processMessage(UUID connectionId, JsonNode payload) {
        boolean fromMe = payload.path("fromMe").asBoolean(false);
        String  from   = payload.path("from").asText("");
        String  to     = payload.path("to").asText("");
        String  chatId = fromMe ? to : from;
        String  msgId  = payload.path("id").asText(payload.path("messageId").asText(""));
        String  body   = payload.path("body").asText(payload.path("content").asText(""));
        boolean isGroup = payload.path("isGroupMsg").asBoolean(chatId.endsWith("@g.us"));

        // Type detection
        String t = payload.path("type").asText("chat");
        Message.MessageType type = switch (t) {
            case "image"    -> Message.MessageType.IMAGE;
            case "video"    -> Message.MessageType.VIDEO;
            case "ptt", "audio" -> Message.MessageType.AUDIO;
            case "document" -> Message.MessageType.DOCUMENT;
            default          -> Message.MessageType.TEXT;
        };

        // Media URL: WPPConnect entrega em `mediaUrl` se baixou o arquivo
        String mediaUrl = payload.path("mediaUrl").asText(null);
        if (mediaUrl == null || mediaUrl.isBlank()) mediaUrl = payload.path("body").isObject() ? null : null;
        String mime     = payload.path("mimetype").asText(null);

        WhatsAppConnection conn = connectionRepo.findById(connectionId).orElse(null);
        if (conn == null) {
            log.warn("[WEBHOOK-WPP] Conexão {} não encontrada — ignorando mensagem", connectionId);
            return;
        }

        String phoneDigits = chatId.split("@")[0].replaceAll("[^0-9]", "");

        dispatcher.dispatch(InboundMessageEvent.builder()
                .connectionId(connectionId)
                .tenantId(conn.getTenantId())
                .externalChatId(chatId)
                .senderPhone(phoneDigits)
                .senderName(payload.path("sender").path("name").asText(payload.path("notifyName").asText("")))
                .externalMessageId(msgId)
                .type(type)
                .content(body)
                .mediaUrl(mediaUrl)
                .mediaMime(mime)
                .receivedAt(Instant.now())
                .fromMe(fromMe)
                .isGroup(isGroup)
                .groupName(isGroup ? payload.path("chat").path("name").asText("") : null)
                .build());
    }

    private void tryAutoDetect(UUID connectionId, JsonNode payload) {
        // Sem event: tenta inferir
        if (payload.hasNonNull("base64Qrimg") || payload.hasNonNull("qrcode")) {
            processQrCode(connectionId, payload);
        } else if (payload.hasNonNull("state")) {
            processStateChange(connectionId, payload);
        } else if (payload.hasNonNull("body") && payload.hasNonNull("from")) {
            processMessage(connectionId, payload);
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
