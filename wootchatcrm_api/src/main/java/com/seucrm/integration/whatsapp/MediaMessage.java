package com.seucrm.integration.whatsapp;

import com.seucrm.domain.conversation.Message;
import lombok.Builder;

@Builder
public record MediaMessage(
    String url,
    String mimeType,
    String caption,
    Message.MessageType type
) {}
