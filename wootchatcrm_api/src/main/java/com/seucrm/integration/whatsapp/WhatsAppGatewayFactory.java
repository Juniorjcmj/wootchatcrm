package com.seucrm.integration.whatsapp;

import com.seucrm.domain.conversation.WhatsAppConnection.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WhatsAppGatewayFactory {

    private final ZApiAdapter zapiAdapter;
    private final WahaAdapter wahaAdapter;
    private final MetaBspAdapter metaBspAdapter;
    private final EvolutionGoAdapter evolutionAdapter;

    public WhatsAppGateway getGateway(ConnectionProvider provider) {
        return switch (provider) {
            case ZAPI -> zapiAdapter;
            case WAHA -> wahaAdapter;
            case META_BSP -> metaBspAdapter;
            case EVOLUTION -> evolutionAdapter;
        };
    }
}
