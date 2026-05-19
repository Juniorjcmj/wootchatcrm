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
 * Adapter para WAHA (https://waha.devlike.pro).
 *
 *  - Cada conexão = 1 sessão dentro de uma instalação WAHA (auto-hospedada).
 *  - Endpoints usados:
 *      POST {base}/api/sessions/start         — inicia uma sessão
 *      POST {base}/api/sessions/{name}/stop   — para a sessão
 *      POST {base}/api/sessions/{name}/logout — limpa a sessão (deve refazer pareamento)
 *      GET  {base}/api/sessions/{name}        — status da sessão
 *      GET  {base}/api/{name}/auth/qr?format=image  — QR atual (PNG base64)
 *      POST {base}/api/sendText               — envia texto
 *      POST {base}/api/sendImage|Video|Voice|File  — envia mídia
 *
 *  - Auth: header `X-Api-Key: <chave configurada no servidor WAHA>`.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WahaAdapter implements WhatsAppGateway {

    private final WebClient                    webClient;
    private final WhatsAppConnectionRepository connectionRepo;
    private final EncryptionService            encryptionService;
    private final ObjectMapper                 objectMapper;

    // ── Credenciais ──────────────────────────────────────────

    private WahaCredentials creds(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));
        try {
            String json = encryptionService.decrypt(conn.getCredentialsEnc());
            return objectMapper.readValue(json, WahaCredentials.class);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao ler credenciais WAHA da conexão " + connectionId, e);
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────

    private JsonNode post(String url, String apiKey, Object body) {
        try {
            return webClient.post()
                    .uri(url)
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body == null ? Map.of() : body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(
                                        new RuntimeException("[WAHA] POST " + url + " → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.error("[WAHA] POST {} falhou: {}", url, e.getMessage());
            throw e;
        }
    }

    JsonNode get(String url, String apiKey) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("X-Api-Key", apiKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(
                                        new RuntimeException("[WAHA] GET " + url + " → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.warn("[WAHA] GET {} falhou: {}", url, e.getMessage());
            throw e;
        }
    }

    // ── WhatsAppGateway interface ────────────────────────────

    @Override
    public SendResult sendText(UUID connectionId, String to, String text) {
        WahaCredentials c = creds(connectionId);
        String chatId = toChatId(to);

        try {
            JsonNode resp = post(
                    c.baseUrl() + "/api/sendText",
                    c.apiKey(),
                    Map.of(
                            "session", c.session(),
                            "chatId",  chatId,
                            "text",    text
                    )
            );
            String msgId = extractMessageId(resp);
            log.info("[WAHA] Texto enviado para {} (session={}) — id: {}", chatId, c.session(), msgId);
            return SendResult.builder().success(true).externalMessageId(msgId).build();
        } catch (Exception e) {
            log.error("[WAHA] sendText falhou para {}: {}", chatId, e.getMessage());
            return SendResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public SendResult sendMedia(UUID connectionId, String to, MediaMessage media) {
        WahaCredentials c = creds(connectionId);
        String chatId = toChatId(to);

        // Endpoint depende do tipo (a WAHA tem rotas separadas por mídia)
        String endpoint = switch (media.type()) {
            case IMAGE    -> "/api/sendImage";
            case VIDEO    -> "/api/sendVideo";
            case AUDIO    -> "/api/sendVoice";
            case DOCUMENT -> "/api/sendFile";
            default       -> "/api/sendImage";
        };

        try {
            JsonNode resp = post(
                    c.baseUrl() + endpoint,
                    c.apiKey(),
                    Map.of(
                            "session", c.session(),
                            "chatId",  chatId,
                            "file",    Map.of("url", media.url()),
                            "caption", media.caption() != null ? media.caption() : ""
                    )
            );
            String msgId = extractMessageId(resp);
            return SendResult.builder().success(true).externalMessageId(msgId).build();
        } catch (Exception e) {
            log.error("[WAHA] sendMedia falhou: {}", e.getMessage());
            return SendResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public SendResult sendTemplate(UUID connectionId, String to, String templateName, List<String> params) {
        // WAHA é baseado em whatsapp-web.js (não tem templates HSM). Envia como texto.
        return sendText(connectionId, to, String.join("\n", params));
    }

    @Override
    public ConnectionStatus getStatus(UUID connectionId) {
        WahaCredentials c = creds(connectionId);
        try {
            JsonNode resp = get(c.baseUrl() + "/api/sessions/" + c.session(), c.apiKey());
            // status: STOPPED | STARTING | SCAN_QR_CODE | WORKING | FAILED
            String status = resp != null ? resp.path("status").asText("") : "";
            boolean working = "WORKING".equalsIgnoreCase(status);
            return ConnectionStatus.builder().connected(working).build();
        } catch (Exception e) {
            log.warn("[WAHA] getStatus falhou para session {}: {}", c.session(), e.getMessage());
            return ConnectionStatus.builder().connected(false).build();
        }
    }

    @Override
    public void disconnect(UUID connectionId) {
        WahaCredentials c = creds(connectionId);
        // logout limpa a sessão (vai exigir QR novo). stop só pausa.
        try {
            post(c.baseUrl() + "/api/sessions/" + c.session() + "/logout", c.apiKey(), Map.of());
            log.info("[WAHA] Sessão {} deslogada", c.session());
        } catch (Exception e) {
            log.warn("[WAHA] Logout falhou para session {}: {}", c.session(), e.getMessage());
        }
    }

    // ── Métodos extras (uso pelo WahaConnectionService) ──────

    /**
     * Inicia (ou recria) a sessão no WAHA configurando o webhook
     * que o CRM expõe em /v1/webhooks/waha/{connectionId}.
     */
    public void startSession(UUID connectionId, String webhookUrl) {
        WahaCredentials c = creds(connectionId);

        Map<String, Object> body = Map.of(
                "name", c.session(),
                "start", true,
                "config", Map.of(
                        "webhooks", List.of(Map.of(
                                "url",    webhookUrl,
                                "events", List.of("message", "session.status")
                        ))
                )
        );

        try {
            post(c.baseUrl() + "/api/sessions/start", c.apiKey(), body);
            log.info("[WAHA] Sessão '{}' iniciada → webhook {}", c.session(), webhookUrl);
        } catch (Exception e) {
            // Se a sessão já existir, WAHA retorna 422. Re-tenta com /restart.
            log.info("[WAHA] /sessions/start falhou ({}), tentando /sessions/{}/restart", e.getMessage(), c.session());
            try {
                post(c.baseUrl() + "/api/sessions/" + c.session() + "/restart", c.apiKey(), Map.of());
                log.info("[WAHA] Sessão '{}' reiniciada", c.session());
            } catch (Exception e2) {
                log.error("[WAHA] Restart também falhou: {}", e2.getMessage());
                throw e2;
            }
        }
    }

    /**
     * Para a sessão (sem deslogar — mantém o pareamento).
     */
    public void stopSession(UUID connectionId) {
        WahaCredentials c = creds(connectionId);
        try {
            post(c.baseUrl() + "/api/sessions/" + c.session() + "/stop", c.apiKey(), Map.of());
        } catch (Exception e) {
            log.warn("[WAHA] stop falhou: {}", e.getMessage());
        }
    }

    /**
     * Pega o QR Code atual (PNG base64 no campo `data`).
     * Endpoint: GET /api/{session}/auth/qr?format=image
     */
    public String getQrCode(UUID connectionId) {
        WahaCredentials c = creds(connectionId);
        try {
            JsonNode resp = get(
                    c.baseUrl() + "/api/" + c.session() + "/auth/qr?format=image",
                    c.apiKey()
            );
            if (resp == null) return null;
            // WAHA retorna { mimetype, data } (data = base64 PNG, SEM o prefixo data:image/png;base64,)
            String data = resp.path("data").asText(null);
            String mime = resp.path("mimetype").asText("image/png");
            if (data == null || data.isBlank()) return null;
            if (data.startsWith("data:")) return data;
            return "data:" + mime + ";base64," + data;
        } catch (Exception e) {
            log.warn("[WAHA] getQrCode falhou: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Apaga completamente a sessão no WAHA (DELETE) — usado pelo reconnect
     * quando a sessão fica em estado ruim.
     */
    public void deleteSession(UUID connectionId) {
        WahaCredentials c = creds(connectionId);
        try {
            webClient.delete()
                    .uri(c.baseUrl() + "/api/sessions/" + c.session())
                    .header("X-Api-Key", c.apiKey())
                    .retrieve().bodyToMono(Void.class).block();
            log.info("[WAHA] Sessão '{}' deletada", c.session());
        } catch (Exception e) {
            log.debug("[WAHA] delete falhou (sessão pode não existir): {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    /** Converte número (com ou sem máscara) pra chatId no formato WAHA: 55119...@c.us */
    private static String toChatId(String to) {
        String digits = to.replaceAll("[^0-9]", "");
        if (to.contains("@")) return to;     // já é chatId
        return digits + "@c.us";
    }

    private static String extractMessageId(JsonNode resp) {
        if (resp == null) return null;
        // WAHA retorna { _data, id: { id, _serialized, ... } } ou { id }
        String[] paths = { "/id/_serialized", "/id/id", "/_data/id/_serialized", "/_data/id/id", "/id" };
        for (String p : paths) {
            String v = resp.at(p).asText(null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }
}
