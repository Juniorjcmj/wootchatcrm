package com.seucrm.integration.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZApiAdapter implements WhatsAppGateway {

    private final WebClient webClient;

    @Override
    public SendResult sendText(UUID connectionId, String to, String text) {
        try {
            String phone = to.replaceAll("[^0-9]", "");
            JsonNode response = webClient.post()
                .uri("https://api.z-api.io/instances/{id}/token/{token}/send-text", "INSTANCE_ID", "TOKEN")
                .bodyValue(Map.of("phone", phone, "message", text))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

            String messageId = response != null ? response.path("zaapId").asText() : null;
            return SendResult.builder().success(messageId != null).externalMessageId(messageId).build();
        } catch (Exception e) {
            log.error("[Z-API] sendText failed: {}", e.getMessage());
            return SendResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public SendResult sendMedia(UUID connectionId, String to, MediaMessage media) {
        return SendResult.builder().success(false).errorMessage("Not implemented").build();
    }

    @Override
    public SendResult sendTemplate(UUID connectionId, String to, String templateName, List<String> params) {
        return sendText(connectionId, to, String.join("\n", params));
    }

    @Override
    public ConnectionStatus getStatus(UUID connectionId) {
        return ConnectionStatus.builder().connected(true).build();
    }

    @Override
    public void disconnect(UUID connectionId) {}
}
