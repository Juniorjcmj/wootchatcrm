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

    // ── Utilitários de requisição ────────────────────────────

    private WebClient.RequestHeadersSpec<?> withAuth(
            WebClient.RequestHeadersSpec<?> spec, String apiKey) {
        return spec.header("apikey", apiKey);
    }

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
                    c.baseUrl() + "/message/sendText/" + c.instanceName(),
                    c.apiKey(),
                    Map.of("number", phone, "text", text, "delay", 1000)
            );

            String msgId = resp != null
                    ? resp.path("key").path("id").asText(null)
                    : null;

            log.info("[EVOLUTION-GO] Texto enviado para {} — id: {}", phone, msgId);
            return SendResult.builder()
                    .success(msgId != null)
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

    @Override
    public SendResult sendMedia(UUID connectionId, String to, MediaMessage media) {
        EvolutionCredentials c = creds(connectionId);
        String phone     = to.replaceAll("[^0-9]", "");
        String mediatype = switch (media.type()) {
            case IMAGE    -> "image";
            case VIDEO    -> "video";
            case AUDIO    -> "audio";
            case DOCUMENT -> "document";
            default       -> "image";
        };

        try {
            JsonNode resp = post(
                    c.baseUrl() + "/message/sendMedia/" + c.instanceName(),
                    c.apiKey(),
                    Map.of(
                            "number",    phone,
                            "mediatype", mediatype,
                            "media",     media.url(),
                            "caption",   media.caption() != null ? media.caption() : ""
                    )
            );

            String msgId = resp != null
                    ? resp.path("key").path("id").asText(null)
                    : null;

            return SendResult.builder()
                    .success(msgId != null)
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
            JsonNode resp = get(
                    c.baseUrl() + "/instance/connectionState/" + c.instanceName(),
                    c.apiKey()
            );

            String state     = resp != null
                    ? resp.path("instance").path("state").asText("close")
                    : "close";
            boolean connected = "open".equals(state);
            String  phone    = resp != null
                    ? resp.path("instance").path("phoneNumber").asText(null)
                    : null;

            return ConnectionStatus.builder()
                    .connected(connected)
                    .phoneNumber(phone)
                    .build();

        } catch (Exception e) {
            log.warn("[EVOLUTION-GO] getStatus falhou para {}: {}", c.instanceName(), e.getMessage());
            return ConnectionStatus.builder().connected(false).build();
        }
    }

    @Override
    public void disconnect(UUID connectionId) {
        EvolutionCredentials c = creds(connectionId);
        delete(c.baseUrl() + "/instance/logout/" + c.instanceName(), c.apiKey());
        log.info("[EVOLUTION-GO] Instância {} desconectada", c.instanceName());
    }

    // ── Métodos extras (gerenciamento de instâncias) ─────────

    /**
     * Lista todas as instâncias disponíveis no servidor Evolution Go.
     * Usado na tela de descoberta do painel de administração.
     */
    public List<EvolutionInstance> listInstances() {
        // URL e apiKey globais — lidos do application.yml
        // Chamado diretamente pelo EvolutionConnectionService (não precisa de connectionId)
        throw new UnsupportedOperationException(
                "Use EvolutionConnectionService.listInstances()");
    }

    /**
     * Configura o webhook de uma instância para apontar para o CRM.
     * Chamado automaticamente após criar uma nova conexão.
     */
    public void setupWebhook(UUID connectionId, String webhookUrl) {
        EvolutionCredentials c = creds(connectionId);

        JsonNode resp = post(
                c.baseUrl() + "/webhook/set/" + c.instanceName(),
                c.apiKey(),
                Map.of(
                        "enabled",         true,
                        "url",             webhookUrl,
                        "webhookByEvents", false,
                        "webhookBase64",   false,
                        "events", List.of(
                                "MESSAGES_UPSERT",
                                "MESSAGES_UPDATE",
                                "CONNECTION_UPDATE",
                                "QRCODE_UPDATED"
                        )
                )
        );

        log.info("[EVOLUTION-GO] Webhook configurado para instância {} → {}",
                c.instanceName(), webhookUrl);
    }

    /**
     * Obtém o QR Code atual da instância (para exibir na tela de configuração).
     */
    public String getQrCode(UUID connectionId) {
        EvolutionCredentials c = creds(connectionId);
        try {
            JsonNode resp = get(
                    c.baseUrl() + "/instance/connect/" + c.instanceName(),
                    c.apiKey()
            );
            return resp != null ? resp.path("base64").asText(null) : null;
        } catch (Exception e) {
            log.warn("[EVOLUTION-GO] getQrCode falhou: {}", e.getMessage());
            return null;
        }
    }
}
