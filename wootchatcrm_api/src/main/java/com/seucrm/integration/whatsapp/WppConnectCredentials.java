package com.seucrm.integration.whatsapp;

/**
 * Credenciais armazenadas (criptografadas) em WhatsAppConnection.credentialsEnc
 * para conexões do tipo WPPCONNECT.
 *
 *   baseUrl       — URL do servidor wppconnect-server (ex.: https://wppconnect.empresa.com)
 *   secretKey     — SECRET_KEY global definida no .env do wppconnect-server.
 *                   Usada para gerar o sessionToken via POST /api/{session}/{SECRET_KEY}/generate-token.
 *   session       — nome da sessão dentro do servidor (cada conexão = 1 sessão)
 *   sessionToken  — Bearer token retornado pelo generate-token; usado em todas as
 *                   chamadas operacionais (start, status, send-*, close, etc.).
 *                   Pode estar vazio se ainda não tiver sido gerado.
 */
public record WppConnectCredentials(
        String baseUrl,
        String secretKey,
        String session,
        String sessionToken) {}
