package com.seucrm.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class NotificationWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendToUser(String userId, NotificationEvent event) {
        messagingTemplate.convertAndSendToUser(userId, "/queue/notifications", event);
        log.debug("Notification sent to user {}: {}", userId, event.type());
    }

    public void broadcast(String destination, Object payload) {
        messagingTemplate.convertAndSend(destination, payload);
    }

    public record NotificationEvent(
        String type,
        String title,
        String body,
        UUID referenceId,
        Instant createdAt
    ) {
        public static NotificationEvent newMessage(UUID conversationId, String leadName) {
            return new NotificationEvent("NEW_MESSAGE", "Nova mensagem", "Nova mensagem de " + leadName, conversationId, Instant.now());
        }

        public static NotificationEvent assignedToYou(UUID conversationId, String leadName) {
            return new NotificationEvent("ASSIGNED_TO_YOU", "Atendimento atribuído", leadName + " foi atribuído a você", conversationId, Instant.now());
        }

        public static NotificationEvent newLead(UUID leadId, String leadName) {
            return new NotificationEvent("NEW_LEAD", "Novo lead", leadName + " entrou no sistema", leadId, Instant.now());
        }
    }
}
