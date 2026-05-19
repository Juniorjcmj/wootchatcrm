package com.seucrm.integration.whatsapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaBspAdapter implements WhatsAppGateway {

    private final WebClient webClient;

    @Override
    public SendResult sendText(UUID connectionId, String to, String text) {
        return SendResult.builder().success(false).errorMessage("Not implemented").build();
    }

    @Override
    public SendResult sendMedia(UUID connectionId, String to, MediaMessage media) {
        return SendResult.builder().success(false).errorMessage("Not implemented").build();
    }

    @Override
    public SendResult sendTemplate(UUID connectionId, String to, String templateName, List<String> params) {
        return SendResult.builder().success(false).errorMessage("Not implemented").build();
    }

    @Override
    public ConnectionStatus getStatus(UUID connectionId) {
        return ConnectionStatus.builder().connected(true).build();
    }

    @Override
    public void disconnect(UUID connectionId) {}
}
