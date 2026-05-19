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

        String event = payload.path("event").asText("");

        // Loga todo evento recebido em INFO para diagnosticar formato real
        String dump = payload.toString();
        log.info("[WEBHOOK-EVOLUTION] event={} conexao={} payload={}",
                event, connectionId,
                dump.length() > 4000 ? dump.substring(0, 4000) + "…(truncado)" : dump);

        switch (event) {
            // Evolution Go (Go) — PascalCase
            case "Message", "SendMessage" -> processMessageUpsert(connectionId, payload);
            case "Receipt"                -> processMessagesUpdate(connectionId, payload);
            case "Connected", "LoggedOut", "Disconnected" -> processConnectionUpdate(connectionId, payload);
            case "QRCode"                 -> processQrCodeUpdate(connectionId, payload);

            // Evolution API (Node) — kept for backward-compat
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
    // Aceita tanto o shape do Evolution Go (data.Info.*) quanto o do Evolution API Node (data.key.*).

    private void processMessageUpsert(UUID connectionId, JsonNode payload) {
        JsonNode data = payload.path("data");

        // Detecta o shape: Evolution Go usa data.Info, Node usa data.key
        boolean isEvoGo = data.hasNonNull("Info");

        JsonNode info     = isEvoGo ? data.path("Info") : data.path("key");
        boolean  fromMe   = isEvoGo ? info.path("IsFromMe").asBoolean(false) : info.path("fromMe").asBoolean(false);

        String chatJid   = isEvoGo ? info.path("Chat").asText("")     : info.path("remoteJid").asText("");
        String senderJid = isEvoGo ? info.path("Sender").asText("")   : info.path("remoteJid").asText("");
        String messageId = isEvoGo ? info.path("ID").asText("")       : info.path("id").asText("");
        boolean isGroup  = isEvoGo ? info.path("IsGroup").asBoolean(false) : chatJid.endsWith("@g.us");

        // Nome do grupo (quando aplicável) — Evolution Go entrega via data.groupData.Name
        String groupName = isGroup && isEvoGo
                ? data.path("groupData").path("Name").asText("")
                : "";

        // Identifica o telefone do CONTATO EXTERNO:
        // - GRUPO: usa o JID do grupo como chave (ex: 120...@g.us). Cada participante é tracked
        //   no pushName de cada mensagem.
        // - 1:1 fromMe=true → contato é o destinatário (RecipientAlt/Chat)
        // - 1:1 fromMe=false → contato é o remetente (SenderAlt/Sender)
        String phone = "";
        if (isGroup) {
            phone = chatJid; // identifica unicamente o grupo
        } else if (isEvoGo) {
            String alt = fromMe
                    ? info.path("RecipientAlt").asText("")
                    : info.path("SenderAlt").asText("");
            if (alt.endsWith("@s.whatsapp.net"))         phone = onlyDigits(alt);
            else if (chatJid.endsWith("@s.whatsapp.net")) phone = onlyDigits(chatJid);
            else if (senderJid.endsWith("@s.whatsapp.net")) phone = onlyDigits(senderJid);
            // se vazio → contato é um LID puro (anônimo); guardamos só o externalChatId
        } else {
            phone = onlyDigits(chatJid);
        }

        String pushName    = isEvoGo
                ? info.path("PushName").asText(null)
                : data.path("pushName").asText(null);

        // Conteúdo + mídia.
        // Evolution Go envia Info.Type como "text", "ExtendedTextMessage" ou "media".
        // Quando é "media", o subtipo (ptt/audio/image/video/document/sticker) está em Info.MediaType,
        // e o objeto correspondente está em data.Message (audioMessage / imageMessage / etc.).
        JsonNode message = isEvoGo ? data.path("Message") : data.path("message");
        String   topType = isEvoGo
                ? info.path("Type").asText("text")
                : data.path("messageType").asText("conversation");
        String   subType = isEvoGo ? info.path("MediaType").asText("") : "";
        String   messageType = resolveMessageType(topType, subType, message);

        String content      = extractContent(message, messageType);
        String mediaUrl     = extractMediaUrl(message, messageType);
        String mediaMime    = extractMediMime(message, messageType);
        // Evolution Go entrega o blob decriptado em base64 (quando WEBHOOK_FILES=true).
        // O caminho varia entre versões; tentamos os mais comuns.
        String mediaBase64 = null;
        if (isEvoGo) {
            String[] candidates = {
                "/data/Message/base64",
                "/data/base64",
                "/data/Message/audioMessage/base64",
                "/data/Message/imageMessage/base64",
                "/data/Message/videoMessage/base64",
                "/data/Message/documentMessage/base64",
                "/data/Message/stickerMessage/base64",
            };
            for (String p : candidates) {
                String v = payload.at(p).asText(null);
                if (v != null && !v.isEmpty()) { mediaBase64 = v; break; }
            }
        }

        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Conexão não encontrada: " + connectionId));

        dispatcher.dispatch(InboundMessageEvent.builder()
                .connectionId(connectionId)
                .tenantId(conn.getTenantId())
                .externalChatId(chatJid)
                .senderPhone(phone)
                .senderName(pushName)
                .externalMessageId(messageId)
                .type(mapMessageType(messageType))
                .content(content)
                .mediaUrl(mediaUrl)
                .mediaMime(mediaMime)
                .mediaBase64(mediaBase64)
                .receivedAt(Instant.now())
                .fromMe(fromMe)
                .isGroup(isGroup)
                .groupName(groupName)
                .build());

        log.info("[WEBHOOK-EVOLUTION] Mensagem despachada — chat={} phone={} fromMe={} group={} type={} id={}",
                chatJid, phone, fromMe, isGroup ? groupName : "no", messageType, messageId);
    }

    private static String onlyDigits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /**
     * Quando Type == "media" (Evolution Go) ou "media"/desconhecido (outros),
     * detecta o subtipo real via Info.MediaType ou via as chaves do data.Message
     * (audioMessage, imageMessage, videoMessage, documentMessage, stickerMessage).
     */
    private static String resolveMessageType(String topType, String subType, JsonNode message) {
        String t = topType == null ? "" : topType.toLowerCase();
        if (!t.equals("media") && !t.isEmpty()) return t;
        // 1) tenta o subtype declarado
        if (subType != null && !subType.isEmpty()) return subType.toLowerCase();
        // 2) inspeciona as chaves do Message para inferir
        if (message != null) {
            if (message.has("audioMessage")    || message.has("AudioMessage"))    return "audio";
            if (message.has("imageMessage")    || message.has("ImageMessage"))    return "image";
            if (message.has("videoMessage")    || message.has("VideoMessage"))    return "video";
            if (message.has("documentMessage") || message.has("DocumentMessage")) return "document";
            if (message.has("stickerMessage")  || message.has("StickerMessage"))  return "sticker";
            if (message.has("locationMessage") || message.has("LocationMessage")) return "location";
            if (message.has("contactMessage")  || message.has("ContactMessage"))  return "contact";
        }
        return "text";
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

    // Extração tolerante: aceita PascalCase (Evolution Go) e camelCase (Evolution Node).
    // Tenta múltiplas chaves para o mesmo campo.
    private static String firstNonEmpty(JsonNode node, String... paths) {
        for (String p : paths) {
            String v = node.at(p.startsWith("/") ? p : "/" + p).asText(null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private String extractContent(JsonNode message, String type) {
        String t = type == null ? "" : type.toLowerCase();
        // Texto simples
        if (t.equals("text") || t.equals("conversation") || t.equals("extendedtextmessage")) {
            return firstNonEmpty(message,
                    "/Conversation", "/conversation",
                    "/ExtendedTextMessage/Text", "/extendedTextMessage/text",
                    "/Text", "/text");
        }
        // Mídia com caption
        if (t.equals("image") || t.equals("imagemessage")) {
            return firstNonEmpty(message, "/ImageMessage/Caption", "/imageMessage/caption");
        }
        if (t.equals("video") || t.equals("videomessage")) {
            return firstNonEmpty(message, "/VideoMessage/Caption", "/videoMessage/caption");
        }
        if (t.equals("document") || t.equals("documentmessage")) {
            return firstNonEmpty(message,
                    "/DocumentMessage/FileName", "/documentMessage/fileName",
                    "/DocumentMessage/Caption", "/documentMessage/caption");
        }
        return null;
    }

    private String extractMediaUrl(JsonNode message, String type) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.equals("image") || t.equals("imagemessage"))
            return firstNonEmpty(message, "/ImageMessage/URL", "/ImageMessage/Url", "/imageMessage/url");
        if (t.equals("video") || t.equals("videomessage"))
            return firstNonEmpty(message, "/VideoMessage/URL", "/VideoMessage/Url", "/videoMessage/url");
        if (t.equals("audio") || t.equals("audiomessage") || t.equals("pttmessage") || t.equals("ptt"))
            return firstNonEmpty(message, "/AudioMessage/URL", "/AudioMessage/Url", "/audioMessage/url");
        if (t.equals("document") || t.equals("documentmessage"))
            return firstNonEmpty(message, "/DocumentMessage/URL", "/DocumentMessage/Url", "/documentMessage/url");
        if (t.equals("sticker") || t.equals("stickermessage"))
            return firstNonEmpty(message, "/StickerMessage/URL", "/StickerMessage/Url", "/stickerMessage/url");
        return null;
    }

    private String extractMediMime(JsonNode message, String type) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.equals("image") || t.equals("imagemessage"))
            return firstNonEmpty(message, "/ImageMessage/Mimetype", "/imageMessage/mimetype");
        if (t.equals("video") || t.equals("videomessage"))
            return firstNonEmpty(message, "/VideoMessage/Mimetype", "/videoMessage/mimetype");
        if (t.equals("audio") || t.equals("audiomessage") || t.equals("pttmessage") || t.equals("ptt")) {
            String v = firstNonEmpty(message, "/AudioMessage/Mimetype", "/audioMessage/mimetype");
            return v != null ? v : "audio/ogg";
        }
        if (t.equals("document") || t.equals("documentmessage"))
            return firstNonEmpty(message, "/DocumentMessage/Mimetype", "/documentMessage/mimetype");
        return null;
    }

    private Message.MessageType mapMessageType(String type) {
        String t = type == null ? "" : type.toLowerCase();
        return switch (t) {
            case "image", "imagemessage"                          -> Message.MessageType.IMAGE;
            case "video", "videomessage"                          -> Message.MessageType.VIDEO;
            case "audio", "audiomessage", "ptt", "pttmessage"     -> Message.MessageType.AUDIO;
            case "document", "documentmessage"                    -> Message.MessageType.DOCUMENT;
            case "sticker", "stickermessage"                      -> Message.MessageType.STICKER;
            case "location", "locationmessage"                    -> Message.MessageType.LOCATION;
            case "contact", "contactmessage"                      -> Message.MessageType.CONTACT;
            default                                               -> Message.MessageType.TEXT;
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
