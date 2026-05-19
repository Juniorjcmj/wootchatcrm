package com.seucrm.api.conversation;

import com.seucrm.config.TenantContext;
import com.seucrm.domain.conversation.*;
import com.seucrm.domain.lead.Lead;
import com.seucrm.domain.lead.LeadRepository;
import com.seucrm.domain.user.User;
import com.seucrm.integration.whatsapp.*;
import com.seucrm.shared.exception.BusinessException;
import com.seucrm.shared.pagination.PageResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

record ConversationSummary(
        UUID id, UUID leadId, UUID connectionId,
        String leadName, String leadPhone, String leadAvatarUrl,
        String channel, String status, UUID assignedTo,
        int unreadCount, String lastMessagePreview, Instant lastMessageAt) {

    static ConversationSummary from(Conversation c) {
        return new ConversationSummary(
                c.getId(), c.getLeadId(), c.getConnectionId(),
                null, null, null,
                c.getChannel().name(), c.getStatus().name(), c.getAssignedTo(),
                c.getUnreadCount(), c.getLastMessagePreview(), c.getLastMessageAt());
    }

    static ConversationSummary from(Conversation c, com.seucrm.domain.lead.Lead lead) {
        return new ConversationSummary(
                c.getId(), c.getLeadId(), c.getConnectionId(),
                lead != null ? lead.getName()      : null,
                lead != null ? lead.getPhone()     : null,
                lead != null ? lead.getAvatarUrl() : null,
                c.getChannel().name(), c.getStatus().name(), c.getAssignedTo(),
                c.getUnreadCount(), c.getLastMessagePreview(), c.getLastMessageAt());
    }
}

record SendMessageRequest(@NotBlank String content, String mediaUrl, String mediaType) {}
record AssignRequest(@NotNull UUID userId) {}

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;
    private final WhatsAppConnectionRepository connectionRepo;
    private final LeadRepository leadRepo;
    private final WhatsAppGatewayFactory gatewayFactory;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional(readOnly = true)
    public PageResponse<ConversationSummary> list(ConversationStatus status, UUID assignedTo, UUID connectionId, int page, int size) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        var pageable = PageRequest.of(page, size);
        return PageResponse.of(
            conversationRepo.findWithFilters(tenantId, status, assignedTo, connectionId, pageable)
                .map(c -> {
                    com.seucrm.domain.lead.Lead lead = c.getLeadId() != null
                            ? leadRepo.findById(c.getLeadId()).orElse(null)
                            : null;
                    return ConversationSummary.from(c, lead);
                })
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageResponse> getMessages(UUID conversationId, int page, int size) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        conversationRepo.findByIdAndTenantId(conversationId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Conversation", conversationId));
        
        var pageable = PageRequest.of(page, size);
        return PageResponse.of(messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId, pageable).map(MessageResponse::from));
    }

    @Transactional
    public MessageResponse sendMessage(UUID conversationId, SendMessageRequest req, User sender) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        
        Conversation conv = conversationRepo.findByIdAndTenantId(conversationId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Conversation", conversationId));
        
        WhatsAppConnection connection = connectionRepo.findById(conv.getConnectionId())
            .orElseThrow(() -> BusinessException.notFound("Connection", conv.getConnectionId()));
        
        Message message = Message.builder()
            .conversationId(conversationId)
            .tenantId(tenantId)
            .direction(Message.MessageDirection.OUTBOUND)
            .senderType(Message.MessageSenderType.AGENT)
            .senderId(sender.getId())
            .type(Message.MessageType.TEXT)
            .content(req.content())
            .status(Message.MessageStatus.PENDING)
            .build();
        
        message = messageRepo.save(message);
        
        // Resolve o destinatário real: usa o phone do lead (ex: "5521965214511"),
        // não o externalChatId (que pode ser um LID anônimo do WhatsApp, "128582747684870@lid").
        String recipient = null;
        if (conv.getLeadId() != null) {
            Lead lead = leadRepo.findById(conv.getLeadId()).orElse(null);
            if (lead != null && lead.getPhone() != null && !lead.getPhone().isBlank()) {
                recipient = lead.getPhone();
            }
        }
        if (recipient == null) recipient = conv.getExternalChatId(); // fallback

        SendResult result;
        // Se foi enviada uma mídia (mediaUrl presente), encaminha como mídia
        if (req.mediaUrl() != null && !req.mediaUrl().isBlank()) {
            // Determina o tipo a partir do mediaType (se fornecido) ou do MIME
            Message.MessageType msgType = Message.MessageType.DOCUMENT;
            if (req.mediaType() != null) {
                String mt = req.mediaType().toLowerCase();
                if (mt.startsWith("image/")) msgType = Message.MessageType.IMAGE;
                else if (mt.startsWith("audio/")) msgType = Message.MessageType.AUDIO;
                else if (mt.startsWith("video/")) msgType = Message.MessageType.VIDEO;
                else msgType = Message.MessageType.DOCUMENT;
            }

            message.setType(msgType);
            message.setMediaUrl(req.mediaUrl());
            message.setMediaMime(req.mediaType());
            message = messageRepo.save(message);

            MediaMessage mm = MediaMessage.builder()
                    .url(req.mediaUrl())
                    .mimeType(req.mediaType())
                    .caption(req.content())
                    .type(msgType)
                    .build();

            result = gatewayFactory.getGateway(connection.getProvider())
                    .sendMedia(connection.getId(), recipient, mm);
        } else {
            result = gatewayFactory.getGateway(connection.getProvider())
                .sendText(connection.getId(), recipient, req.content());
        }
        
        message.setStatus(result != null && result.isSuccess() ? Message.MessageStatus.SENT : Message.MessageStatus.FAILED);
        if (result != null) {
            message.setExternalId(result.getExternalMessageId());
            message.setErrorMessage(result.getErrorMessage());
        }
        message = messageRepo.save(message);
        
        conv.setLastMessageAt(Instant.now());
        conv.setLastMessagePreview((req.content() != null && req.content().length() > 100) ? req.content().substring(0, 100) + "..." : req.content());
        if (conv.getStatus() == ConversationStatus.PENDING) {
            conv.setStatus(ConversationStatus.OPEN);
        }
        conversationRepo.save(conv);
        
        MessageResponse response = MessageResponse.from(message);
        messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, response);
        
        return response;
    }

    @Transactional
    public ConversationSummary assign(UUID conversationId, UUID userId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Conversation conv = conversationRepo.findByIdAndTenantId(conversationId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Conversation", conversationId));
        
        conv.setAssignedTo(userId);
        if (conv.getStatus() == ConversationStatus.PENDING) {
            conv.setStatus(ConversationStatus.OPEN);
        }
        
        return ConversationSummary.from(conversationRepo.save(conv));
    }

    @Transactional
    public ConversationSummary finish(UUID conversationId, UUID finishedBy) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Conversation conv = conversationRepo.findByIdAndTenantId(conversationId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Conversation", conversationId));
        
        conv.setStatus(ConversationStatus.FINISHED);
        conv.setFinishedAt(Instant.now());
        conv.setFinishedBy(finishedBy);
        
        return ConversationSummary.from(conversationRepo.save(conv));
    }
}
