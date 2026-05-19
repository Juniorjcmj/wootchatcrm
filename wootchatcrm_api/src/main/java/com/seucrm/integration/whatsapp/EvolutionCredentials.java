package com.seucrm.integration.whatsapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Credenciais armazenadas criptografadas em WhatsAppConnection.credentialsEnc
// Estrutura JSON:
// { "baseUrl": "...", "apiKey": "...", "instanceName": "..." }

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvolutionCredentials(
        String baseUrl,
        String apiKey,
        String instanceName
) {}
