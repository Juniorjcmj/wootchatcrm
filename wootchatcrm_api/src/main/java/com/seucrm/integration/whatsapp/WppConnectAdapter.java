package com.seucrm.integration.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seucrm.domain.conversation.WhatsAppConnection;
import com.seucrm.domain.conversation.WhatsAppConnectionRepository;
import com.seucrm.shared.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter para WPPConnect Server (https://github.com/wppconnect-team/wppconnect-server).
 *
 *  - 1 conexão = 1 "sessão" dentro do servidor.
 *  - Autenticação em dois níveis:
 *      1) SECRET_KEY global (env do server) → usada UMA vez para gerar o
 *         Bearer token da sessão via POST /api/{session}/{SECRET_KEY}/generate-token
 *      2) Bearer {sessionToken} → vai no header Authorization de todas as
 *         outras chamadas (start-session, status-session, send-message, …).
 *
 *  - Endpoints relevantes:
 *      POST /api/{session}/{SECRET_KEY}/generate-token   → { token: "..." }
 *      POST /api/{session}/start-session                 → inicia sessão + retorna QR
 *      GET  /api/{session}/qrcode-session                → PNG base64 do QR atual
 *      GET  /api/{session}/status-session                → { status: "CLOSED"|"QRCODE"|"CONNECTED"|... }
 *      POST /api/{session}/close-session                 → para a sessão (mantém pareamento)
 *      POST /api/{session}/logout-session                → faz logout (exige novo QR)
 *      POST /api/{session}/send-message                  → texto
 *      POST /api/{session}/send-image                    → imagem (base64 ou path)
 *      POST /api/{session}/send-file                     → documento
 *      POST /api/{session}/send-voice                    → PTT (audio)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WppConnectAdapter implements WhatsAppGateway {

    private final WebClient                    webClient;
    private final WhatsAppConnectionRepository connectionRepo;
    private final EncryptionService            encryptionService;
    private final ObjectMapper                 objectMapper;

    // ── Credenciais ──────────────────────────────────────────

    private WppConnectCredentials creds(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));
        try {
            String json = encryptionService.decrypt(conn.getCredentialsEnc());
            return objectMapper.readValue(json, WppConnectCredentials.class);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao ler credenciais WPPConnect da conexão " + connectionId, e);
        }
    }

    // ── HTTP helpers (suportam ambos os esquemas de auth) ────

    /** Auth com Bearer sessionToken (uso geral). */
    private JsonNode postBearer(String url, String token, Object body) {
        try {
            return webClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + (token == null ? "" : token))
                    .header("Content-Type", "application/json")
                    .bodyValue(body == null ? Map.of() : body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(
                                        new RuntimeException("[WPP] POST " + url + " → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.error("[WPP] POST {} falhou: {}", url, e.getMessage());
            throw e;
        }
    }

    private JsonNode getBearer(String url, String token) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + (token == null ? "" : token))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(
                                        new RuntimeException("[WPP] GET " + url + " → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.warn("[WPP] GET {} falhou: {}", url, e.getMessage());
            throw e;
        }
    }

    /** Gera um sessionToken via SECRET_KEY (sem auth Bearer). */
    public String generateSessionToken(String baseUrl, String secretKey, String session) {
        try {
            JsonNode resp = webClient.post()
                    .uri(baseUrl + "/api/" + session + "/" + secretKey + "/generate-token")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r ->
                            r.bodyToMono(String.class)
                             .flatMap(err -> Mono.error(
                                     new RuntimeException("[WPP] generate-token → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) throw new RuntimeException("Resposta vazia do generate-token");
            // Resposta tem o token tanto em "token" quanto em "full" — pegamos "token".
            String token = resp.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Token não retornado pelo WPPConnect: " + resp);
            }
            return token;
        } catch (Exception e) {
            log.error("[WPP] Falha ao gerar token para session {}: {}", session, e.getMessage());
            throw e;
        }
    }

    // ── WhatsAppGateway interface ────────────────────────────

    @Override
    public SendResult sendText(UUID connectionId, String to, String text) {
        WppConnectCredentials c = creds(connectionId);
        String phone = onlyDigits(to);

        try {
            JsonNode resp = postBearer(
                    c.baseUrl() + "/api/" + c.session() + "/send-message",
                    c.sessionToken(),
                    Map.of("phone", phone, "message", text, "isGroup", to.endsWith("@g.us"))
            );
            String msgId = extractMessageId(resp);
            log.info("[WPP] Texto enviado para {} (session={}) — id: {}", phone, c.session(), msgId);
            return SendResult.builder().success(true).externalMessageId(msgId).build();
        } catch (Exception e) {
            log.error("[WPP] sendText falhou para {}: {}", phone, e.getMessage());
            return SendResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public SendResult sendMedia(UUID connectionId, String to, MediaMessage media) {
        WppConnectCredentials c = creds(connectionId);
        String phone = onlyDigits(to);

        String endpoint = switch (media.type()) {
            case IMAGE    -> "/send-image";
            case VIDEO    -> "/send-file";   // WPPConnect manda vídeo como file (com mimetype certo)
            case AUDIO    -> "/send-voice";
            case DOCUMENT -> "/send-file";
            default       -> "/send-image";
        };

        // WPPConnect aceita base64 OU path (URL externa). Como nosso MediaMessage usa URL,
        // mandamos como `path`. Para AUDIO o WPP aceita `path` em versões recentes.
        String filename = inferFilenameFromUrl(media.url());
        Map<String, Object> body = Map.of(
                "phone",    phone,
                "path",     media.url() != null ? media.url() : "",
                "filename", filename,
                "caption",  media.caption() != null ? media.caption() : "",
                "isGroup",  to.endsWith("@g.us")
        );

        try {
            JsonNode resp = postBearer(c.baseUrl() + "/api/" + c.session() + endpoint, c.sessionToken(), body);
            String msgId = extractMessageId(resp);
            return SendResult.builder().success(true).externalMessageId(msgId).build();
        } catch (Exception e) {
            log.error("[WPP] sendMedia falhou: {}", e.getMessage());
            return SendResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public SendResult sendTemplate(UUID connectionId, String to, String templateName, List<String> params) {
        // WPPConnect não tem templates oficiais — manda como texto formatado.
        return sendText(connectionId, to, String.join("\n", params));
    }

    @Override
    public ConnectionStatus getStatus(UUID connectionId) {
        WppConnectCredentials c = creds(connectionId);
        try {
            JsonNode resp = getBearer(
                    c.baseUrl() + "/api/" + c.session() + "/status-session",
                    c.sessionToken()
            );
            // status: CLOSED | INITIALIZING | QRCODE | CONNECTED | DISCONNECTED | PAIRING
            String status = resp != null ? resp.path("status").asText("") : "";
            boolean connected = "CONNECTED".equalsIgnoreCase(status)
                             || "inChat".equalsIgnoreCase(status);
            return ConnectionStatus.builder().connected(connected).build();
        } catch (Exception e) {
            log.warn("[WPP] getStatus falhou para session {}: {}", c.session(), e.getMessage());
            return ConnectionStatus.builder().connected(false).build();
        }
    }

    @Override
    public void disconnect(UUID connectionId) {
        WppConnectCredentials c = creds(connectionId);
        try {
            postBearer(c.baseUrl() + "/api/" + c.session() + "/logout-session", c.sessionToken(), Map.of());
            log.info("[WPP] Sessão '{}' deslogada", c.session());
        } catch (Exception e) {
            log.warn("[WPP] Logout falhou: {}", e.getMessage());
        }
    }

    // ── Métodos extras (uso pelo WppConnectConnectionService) ─

    /**
     * Inicia a sessão configurando o webhook do CRM.
     * Doc: POST /api/{session}/start-session  body: { webhook, waitQrCode }
     */
    public void startSession(UUID connectionId, String webhookUrl) {
        WppConnectCredentials c = creds(connectionId);
        try {
            postBearer(
                    c.baseUrl() + "/api/" + c.session() + "/start-session",
                    c.sessionToken(),
                    Map.of(
                            "webhook",    webhookUrl,
                            "waitQrCode", false
                    )
            );
            log.info("[WPP] Sessão '{}' iniciada → webhook {}", c.session(), webhookUrl);
        } catch (Exception e) {
            log.error("[WPP] start-session falhou: {}", e.getMessage());
            throw e;
        }
    }

    /** Para a sessão sem deslogar. */
    public void closeSession(UUID connectionId) {
        WppConnectCredentials c = creds(connectionId);
        try {
            postBearer(c.baseUrl() + "/api/" + c.session() + "/close-session", c.sessionToken(), Map.of());
        } catch (Exception e) {
            log.warn("[WPP] close-session falhou: {}", e.getMessage());
        }
    }

    /**
     * Recupera o QR Code atual (PNG base64 com prefixo `data:image/png;base64,`).
     * Doc: GET /api/{session}/qrcode-session
     */
    public String getQrCode(UUID connectionId) {
        WppConnectCredentials c = creds(connectionId);
        try {
            // WPPConnect retorna a imagem PNG diretamente (binário) por padrão.
            // Pedimos com Accept: application/json pra forçar JSON com base64.
            JsonNode resp = webClient.get()
                    .uri(c.baseUrl() + "/api/" + c.session() + "/qrcode-session")
                    .header("Authorization", "Bearer " + c.sessionToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return null;
            // Tenta os caminhos conhecidos: { qrcode: "data:..." } | { base64Qr: "..." } | { qr: "..." }
            String[] keys = { "qrcode", "base64Qr", "qr", "base64" };
            for (String k : keys) {
                String v = resp.path(k).asText(null);
                if (v != null && !v.isBlank()) {
                    return v.startsWith("data:") ? v : ("data:image/png;base64," + v);
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[WPP] getQrCode falhou: {}", e.getMessage());
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private static String onlyDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
    }

    private static String inferFilenameFromUrl(String url) {
        if (url == null || url.isBlank()) return "file";
        int q = url.indexOf('?');
        String clean = q > 0 ? url.substring(0, q) : url;
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        return name.isBlank() ? "file" : name;
    }

    private static String extractMessageId(JsonNode resp) {
        if (resp == null) return null;
        // WPPConnect retorna { response: { id, ... } } ou direto { id }
        String[] paths = { "/response/id", "/id", "/response/messageId" };
        for (String p : paths) {
            String v = resp.at(p).asText(null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }
}
