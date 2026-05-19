package com.seucrm.integration.whatsapp;

/**
 * Credenciais armazenadas (criptografadas) em WhatsAppConnection.credentialsEnc
 * para conexões do tipo WAHA.
 *
 *   baseUrl   — URL completa do servidor WAHA (ex.: https://waha.empresa.com)
 *   apiKey    — X-Api-Key configurada no servidor WAHA (WHATSAPP_API_KEY do .env do WAHA)
 *   session   — nome da sessão dentro do WAHA (cada conexão = 1 session)
 */
public record WahaCredentials(String baseUrl, String apiKey, String session) {}
