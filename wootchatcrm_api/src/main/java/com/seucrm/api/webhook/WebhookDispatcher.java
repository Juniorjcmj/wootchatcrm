package com.seucrm.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.seucrm.domain.conversation.*;
import com.seucrm.domain.lead.Lead;
import com.seucrm.domain.lead.LeadChannel;
import com.seucrm.domain.lead.LeadRepository;
import com.seucrm.integration.whatsapp.InboundMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;
    private final WhatsAppConnectionRepository connectionRepo;
    private final LeadRepository leadRepo;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void dispatch(InboundMessageEvent event) {
        try {
            WhatsAppConnection connection = connectionRepo.findById(event.connectionId())
                .orElseThrow(() -> new RuntimeException("Connection not found: " + event.connectionId()));
            
            UUID tenantId = connection.getTenantId();

            // Identifica/cria o lead.
            // - GRUPO: chave = JID do grupo (@g.us). Nome = nome do grupo. Cada lead-grupo agrupa todas as mensagens.
            // - 1:1: chave = phone (ou JID se LID puro). Nome = pushName do contato.
            String leadKey;
            String leadName;
            if (event.isGroup()) {
                leadKey  = event.externalChatId();
                leadName = (event.groupName() != null && !event.groupName().isBlank())
                        ? event.groupName() : "Grupo";
            } else {
                leadKey  = (event.senderPhone() != null && !event.senderPhone().isBlank())
                        ? event.senderPhone() : event.externalChatId();
                leadName = (event.senderName() != null && !event.senderName().isBlank())
                        ? event.senderName() : leadKey;
            }
            final String leadKeyFinal  = leadKey;
            final String leadNameFinal = leadName;

            Lead lead = leadRepo.findByTenantIdAndPhone(tenantId, leadKeyFinal)
                .orElseGet(() -> leadRepo.save(Lead.builder()
                    .tenantId(tenantId)
                    .name(leadNameFinal)
                    .phone(leadKeyFinal)
                    .channel(LeadChannel.WHATSAPP)
                    .externalId(leadKeyFinal)
                    .build()));

            // Normaliza externalChatId:
            // - GRUPO: usa o próprio JID do grupo (@g.us) — único e estável.
            // - 1:1: Evolution Go às vezes manda LID anônimo, outras vezes phone — padronizamos pra <phone>@s.whatsapp.net.
            String chatId = event.externalChatId();
            if (!event.isGroup() && event.senderPhone() != null && !event.senderPhone().isBlank()) {
                chatId = event.senderPhone() + "@s.whatsapp.net";
            }
            final String externalChatId = chatId;

            // Tenta primeiro pelo chatId normalizado; se não achar, faz fallback ao chatId original
            // (compatibilidade com conversas antigas criadas com LID).
            Conversation conversation = conversationRepo
                .findByTenantIdAndExternalChatId(tenantId, externalChatId)
                .or(() -> conversationRepo.findByTenantIdAndExternalChatId(tenantId, event.externalChatId()))
                .orElseGet(() -> conversationRepo.save(Conversation.builder()
                    .tenantId(tenantId)
                    .leadId(lead.getId())
                    .connectionId(event.connectionId())
                    .channel(LeadChannel.WHATSAPP)
                    .status(ConversationStatus.PENDING)
                    .externalChatId(externalChatId)
                    .build()));

            if (event.externalMessageId() != null && messageRepo.findByTenantIdAndExternalId(tenantId, event.externalMessageId()).isPresent()) {
                log.debug("Duplicate message ignored: {}", event.externalMessageId());
                return;
            }

            // Direção / autor baseado em fromMe
            Message.MessageDirection  direction  = event.fromMe() ? Message.MessageDirection.OUTBOUND : Message.MessageDirection.INBOUND;
            Message.MessageSenderType senderType = event.fromMe() ? Message.MessageSenderType.AGENT    : Message.MessageSenderType.LEAD;

            // Em grupos, prefixa o content com o nome do participante pra UX (ex: "[João] Bom dia").
            String content = event.content();
            if (event.isGroup() && !event.fromMe()
                    && event.senderName() != null && !event.senderName().isBlank()) {
                String prefix = "[" + event.senderName() + "] ";
                content = (content != null && !content.isBlank()) ? prefix + content : prefix.trim();
            }

            Message message = Message.builder()
                .conversationId(conversation.getId())
                .tenantId(tenantId)
                .direction(direction)
                .senderType(senderType)
                .type(event.type())
                .content(content)
                .mediaUrl(event.mediaUrl())
                .mediaMime(event.mediaMime())
                .mediaSizeBytes(event.mediaSizeBytes())
                .mediaDurationS(event.mediaDurationS())
                .mediaBase64(event.mediaBase64())
                .externalId(event.externalMessageId())
                .status(Message.MessageStatus.DELIVERED)
                .build();

            message = messageRepo.save(message);

            String previewSrc = content != null ? content : "[mídia]";
            String preview = previewSrc.length() > 100 ? previewSrc.substring(0, 100) + "..." : previewSrc;

            conversation.setLastMessageAt(Instant.now());
            conversation.setLastMessagePreview(preview);
            // Só incrementa unread em mensagens recebidas
            if (!event.fromMe()) {
                conversation.setUnreadCount(conversation.getUnreadCount() + 1);
                if (conversation.getStatus() == ConversationStatus.FINISHED) {
                    conversation.setStatus(ConversationStatus.WAITING);
                }
            }
            conversationRepo.save(conversation);

            // Envia o shape público (MessageResponse) — sem media_base64 (poderia ser MB)
            // e com hasMedia derivado para o frontend renderizar previews.
            messagingTemplate.convertAndSend(
                "/topic/conversation/" + conversation.getId(),
                com.seucrm.api.conversation.MessageResponse.from(message));
            messagingTemplate.convertAndSend("/topic/conversations/updates", new ConversationUpdate(conversation.getId(), conversation.getStatus().name(), conversation.getUnreadCount()));

            log.info("Message dispatched: conversation={} lead={} fromMe={}",
                    conversation.getId(), lead.getId(), event.fromMe());
        } catch (Exception e) {
            log.error("Webhook dispatch failed for connection {}: {}", event.connectionId(), e.getMessage(), e);
        }
    }

    public record ConversationUpdate(UUID conversationId, String status, int unreadCount) {}
}
