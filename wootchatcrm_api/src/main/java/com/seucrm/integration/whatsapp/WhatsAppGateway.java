package com.seucrm.integration.whatsapp;

import java.util.UUID;

public interface WhatsAppGateway {
    SendResult sendText(UUID connectionId, String to, String text);
    SendResult sendMedia(UUID connectionId, String to, MediaMessage media);
    SendResult sendTemplate(UUID connectionId, String to, String templateName, java.util.List<String> params);
    ConnectionStatus getStatus(UUID connectionId);
    void disconnect(UUID connectionId);
}
