package com.seucrm.websocket;

import com.seucrm.domain.conversation.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ConversationRepository conversationRepo;

    @MessageMapping("/conversation/{conversationId}/typing")
    public void typing(
        @DestinationVariable UUID conversationId,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        Principal user = headerAccessor.getUser();
        if (user == null) return;
        
        TypingEvent event = new TypingEvent(conversationId, user.getName(), Instant.now());
        messagingTemplate.convertAndSend("/topic/conversation/" + conversationId + "/typing", event);
    }

    @MessageMapping("/conversation/{conversationId}/read")
    public void markRead(
        @DestinationVariable UUID conversationId,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        Principal user = headerAccessor.getUser();
        if (user == null) return;
        
        conversationRepo.findById(conversationId).ifPresent(conv -> {
            conv.setUnreadCount(0);
            conversationRepo.save(conv);
            messagingTemplate.convertAndSend(
                "/topic/conversation/" + conversationId + "/read",
                new ReadEvent(conversationId, user.getName(), Instant.now())
            );
        });
    }

    public record TypingEvent(UUID conversationId, String userName, Instant timestamp) {}
    public record ReadEvent(UUID conversationId, String userName, Instant timestamp) {}
}
