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
 * Adapter para Evolution Go (Golang).
 * Doc: https://docs.evolutionfoundation.com.br/evolution-go
 *
 * Auth: cada instância tem um `token` (UUID) retornado em /instance/create.
 * Esse token vira o `apikey` usado em todas as chamadas operacionais
 * (qr, status, send, disconnect, logout). O instanceName não vai mais no path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionGoAdapter implements WhatsAppGateway {

    private final WebClient                    webClient;
    private final WhatsAppConnectionRepository connectionRepo;
    private final EncryptionService            encryptionService;
    private final ObjectMapper                 objectMapper;

    // ── Credenciais ──────────────────────────────────────────

    private EvolutionCredentials creds(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));
        try {
            String json = encryptionService.decrypt(conn.getCredentialsEnc());
            return objectMapper.readValue(json, EvolutionCredentials.class);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao ler credenciais da conexão " + connectionId, e);
        }
    }

    // ── Utilitários ──────────────────────────────────────────

    private JsonNode post(String url, String apiKey, Object body) {
        try {
            return webClient.post()
                    .uri(url)
                    .header("apikey", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(
                                    new RuntimeException("[EVOLUTION-GO] POST " + url + " → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.error("[EVOLUTION-GO] POST {} falhou: {}", url, e.getMessage());
            throw e;
        }
    }

    private JsonNode get(String url, String apiKey) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("apikey", apiKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(err -> Mono.error(
                                    new RuntimeException("[EVOLUTION-GO] GET " + url + " → " + err))))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.error("[EVOLUTION-GO] GET {} falhou: {}", url, e.getMessage());
            throw e;
        }
    }

    private void delete(String url, String apiKey) {
        try {
            webClient.delete()
                    .uri(url)
                    .header("apikey", apiKey)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.warn("[EVOLUTION-GO] DELETE {} falhou: {}", url, e.getMessage());
        }
    }

    // ── WhatsAppGateway interface ────────────────────────────

    @Override
    public SendResult sendText(UUID connectionId, String to, String text) {
        EvolutionCredentials c = creds(connectionId);
        String phone = to.replaceAll("[^0-9]", "");

        try {
            JsonNode resp = post(
                    c.baseUrl() + "/send/text",
                    c.apiKey(),
                    Map.of("number", phone, "text", text, "delay", 0)
            );

            // Evolution Go retorna o ID em data.Info.ID (PascalCase)
            String msgId = extractMessageId(resp);

            log.info("[EVOLUTION-GO] Texto enviado para {} — id: {}", phone, msgId);
            return SendResult.builder()
                    .success(resp != null && "success".equals(resp.path("message").asText()))
                    .externalMessageId(msgId)
                    .build();

        } catch (Exception e) {
            log.error("[EVOLUTION-GO] sendText falhou para {}: {}", phone, e.getMessage());
            return SendResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private static String extractMessageId(JsonNode resp) {
        if (resp == null) return null;
        String[] paths = {
            "/data/Info/ID",   // Evolution Go: PascalCase
            "/data/id",        // legacy
            "/data/key/id",    // Evolution Node API
        };
        for (String p : paths) {
            String v = resp.at(p).asText(null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    @Override
    public SendResult sendMedia(UUID connectionId, String to, MediaMessage media) {
        EvolutionCredentials c = creds(connectionId);
        String phone = to.replaceAll("[^0-9]", "");
        String type = switch (media.type()) {
            case IMAGE    -> "image";
            case VIDEO    -> "video";
            case AUDIO    -> "audio";
            case DOCUMENT -> "document";
            default       -> "image";
        };

        try {
            JsonNode resp = post(
                    c.baseUrl() + "/send/media",
                    c.apiKey(),
                    Map.of(
                            "number",  phone,
                            "type",    type,
                            "url",     media.url(),
                            "caption", media.caption() != null ? media.caption() : "",
                            "delay",   0
                    )
            );

            String msgId = extractMessageId(resp);

            return SendResult.builder()
                    .success(resp != null && "success".equals(resp.path("message").asText()))
                    .externalMessageId(msgId)
                    .build();

        } catch (Exception e) {
            log.error("[EVOLUTION-GO] sendMedia falhou: {}", e.getMessage());
            return SendResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public SendResult sendTemplate(UUID connectionId, String to,
                                   String templateName, List<String> params) {
        // Evolution Go não tem templates nativos — envia como texto formatado
        String text = String.join("\n", params);
        return sendText(connectionId, to, text);
    }

    @Override
    public ConnectionStatus getStatus(UUID connectionId) {
        EvolutionCredentials c = creds(connectionId);
        try {
            // GET /instance/status → { "data": { "Connected": bool, "LoggedIn": bool, "Name": "" } }
            // ATENÇÃO:
            //   - "Connected" = só o WebSocket com o WhatsApp está aberto (vira true assim que cria a instância)
            //   - "LoggedIn"  = sessão pareada com um número real (só fica true após o QR ser escaneado)
            // Pra nossa UX, "conectado" significa "vinculado a um WhatsApp" → usamos LoggedIn.
            JsonNode resp = get(c.baseUrl() + "/instance/status", c.apiKey());

            boolean loggedIn = resp != null
                    && resp.path("data").path("LoggedIn").asBoolean(false);

            return ConnectionStatus.builder()
                    .connected(loggedIn)
                    .build();

        } catch (Exception e) {
            log.warn("[EVOLUTION-GO] getStatus falhou para {}: {}", c.instanceName(), e.getMessage());
            return ConnectionStatus.builder().connected(false).build();
        }
    }

    @Override
    public void disconnect(UUID connectionId) {
        EvolutionCredentials c = creds(connectionId);
        // Evolution Go: DELETE /instance/logout (com apikey da instância)
        delete(c.baseUrl() + "/instance/logout", c.apiKey());
        log.info("[EVOLUTION-GO] Instância {} desconectada (logout)", c.instanceName());
    }

    // ── Métodos extras (gerenciamento de instâncias) ─────────

    /**
     * Configura webhook + assina eventos no Evolution Go e inicia a sessão.
     * Doc: POST /instance/connect  body: { webhookUrl, subscribe, immediate, phone? }
     *
     * IMPORTANTE: `immediate: true` faz o Evolution Go tentar pareamento por *telefone*
     * (chama /instance/pair internamente) — sem o `phone` setado isso falha e nunca emite
     * QR. Mantemos `immediate: false` pra forçar o fluxo de QR Code.
     */
    public void setupWebhook(UUID connectionId, String webhookUrl) {
        EvolutionCredentials c = creds(connectionId);

        post(
                c.baseUrl() + "/instance/connect",
                c.apiKey(),
                Map.of(
                        "webhookUrl", webhookUrl,
                        "subscribe",  List.of("ALL"),
                        "immediate",  false
                )
        );

        log.info("[EVOLUTION-GO] Webhook configurado para instância {} → {}",
                c.instanceName(), webhookUrl);
    }

    /**
     * Pega o QR Code atual da instância para exibir na tela de configuração.
     * Doc: GET /instance/qr → { "data": { "Qrcode": "data:image/png;base64,...", "Code": "..." } }
     */
    public String getQrCode(UUID connectionId) {
        EvolutionCredentials c = creds(connectionId);
        try {
            JsonNode resp = get(c.baseUrl() + "/instance/qr", c.apiKey());
            // Field name é "Qrcode" (Q maiúsculo) na resposta do Evolution Go
            return resp != null ? resp.path("data").path("Qrcode").asText(null) : null;
        } catch (Exception e) {
            log.warn("[EVOLUTION-GO] getQrCode falhou: {}", e.getMessage());
            return null;
        }
    }
}
