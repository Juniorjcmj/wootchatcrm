package com.seucrm.integration.whatsapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// ── Credenciais armazenadas criptografadas em WhatsAppConnection.credentialsEnc ──
// Estrutura JSON:
// {
//   "baseUrl":      "https://evovo.wootchat.com.br",
//   "apiKey":       "<EVOLUTION_API_KEY>",
//   "instanceName": "vendas-01"
// }

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvolutionCredentials(
        String baseUrl,
        String apiKey,
        String instanceName
) {}

// ── Instância retornada pelo GET /instances ───────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvolutionInstance(
        String instanceName,
        String instanceId,
        String status,           // open | close | connecting
        String phoneNumber,
        String profileName,
        String profilePicUrl
) {}
