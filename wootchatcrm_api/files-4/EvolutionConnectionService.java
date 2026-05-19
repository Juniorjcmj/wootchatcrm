package com.seucrm.api.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seucrm.domain.conversation.WhatsAppConnection;
import com.seucrm.domain.conversation.WhatsAppConnectionRepository;
import com.seucrm.integration.whatsapp.EvolutionCredentials;
import com.seucrm.integration.whatsapp.EvolutionGoAdapter;
import com.seucrm.integration.whatsapp.EvolutionInstance;
import com.seucrm.shared.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvolutionConnectionService {

    // ── Config ───────────────────────────────────────────────
    // Lidos do application.yml:
    //   app.evolution.base-url = https://evovo.wootchat.com.br
    //   app.evolution.api-key  = <EVOLUTION_API_KEY>
    //   app.whatsapp.webhook-base-url = https://api.seucrm.com.br/api

    @Value("${app.evolution.base-url}")
    private String evolutionBaseUrl;

    @Value("${app.evolution.api-key}")
    private String evolutionApiKey;

    @Value("${app.whatsapp.webhook-base-url}")
    private String webhookBaseUrl;

    private final WhatsAppConnectionRepository connectionRepo;
    private final EncryptionService            encryptionService;
    private final EvolutionGoAdapter           evolutionAdapter;
    private final ObjectMapper                 objectMapper;
    private final WebClient                    webClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate        messagingTemplate;

    // ── Criar nova instância no Evolution Go ─────────────────

    @Transactional
    public WhatsAppConnection createConnection(
            UUID   tenantId,
            String displayName,
            String instanceName,
            UUID   createdBy) {

        log.info("[EVOLUTION] Criando instância '{}' no Evolution Go para tenant {}",
                instanceName, tenantId);

        // 1. Criar instância no Evolution Go
        // POST https://evovo.wootchat.com.br/instance/create
        JsonNode resp = webClient.post()
                .uri(evolutionBaseUrl + "/instance/create")
                .header("apikey", evolutionApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "instanceName", instanceName,
                        "qrcode",       true
                ))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (resp == null) {
            throw new RuntimeException("Evolution Go não retornou resposta ao criar instância");
        }

        // 2. Extrair instanceApiKey da resposta
        // Evolution Go retorna: { "instance": {...}, "hash": { "apikey": "..." } }
        String instanceApiKey = resp.path("hash").path("apikey").asText(null);
        if (instanceApiKey == null || instanceApiKey.isBlank()) {
            // Alguns builds retornam direto no campo "apikey"
            instanceApiKey = resp.path("apikey").asText(evolutionApiKey);
        }

        log.info("[EVOLUTION] Instância '{}' criada com sucesso", instanceName);

        // 3. Montar JSON de credenciais e criptografar
        EvolutionCredentials credentials = new EvolutionCredentials(
                evolutionBaseUrl,
                instanceApiKey,
                instanceName
        );

        String credentialsJson;
        try {
            credentialsJson = objectMapper.writeValueAsString(credentials);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao serializar credenciais", e);
        }

        String credentialsEnc = encryptionService.encrypt(credentialsJson);

        // 4. Salvar WhatsAppConnection no banco
        WhatsAppConnection connection = WhatsAppConnection.builder()
                .tenantId(tenantId)
                .name(displayName)
                .provider(WhatsAppConnection.ConnectionProvider.EVOLUTION)
                .credentialsEnc(credentialsEnc)
                .webhookToken(UUID.randomUUID().toString()) // token para validar webhooks
                .active(true)
                .connected(false)
                .createdBy(createdBy)
                .build();

        connection = connectionRepo.save(connection);
        UUID connectionId = connection.getId();

        // 5. Configurar webhook no Evolution Go apontando para o CRM
        // URL: https://api.seucrm.com.br/api/v1/webhooks/evolution/{connectionId}
        String webhookUrl = webhookBaseUrl + "/v1/webhooks/evolution/" + connectionId;
        evolutionAdapter.setupWebhook(connectionId, webhookUrl);

        log.info("[EVOLUTION] Conexão {} criada. Webhook: {}", connectionId, webhookUrl);

        return connection;
    }

    // ── Listar instâncias disponíveis no Evolution Go ────────

    public List<EvolutionInstance> listAvailableInstances() {
        // GET https://evovo.wootchat.com.br/instances
        try {
            EvolutionInstance[] instances = webClient.get()
                    .uri(evolutionBaseUrl + "/instances")
                    .header("apikey", evolutionApiKey)
                    .retrieve()
                    .bodyToMono(EvolutionInstance[].class)
                    .block();

            return instances != null ? List.of(instances) : List.of();

        } catch (Exception e) {
            log.error("[EVOLUTION] Falha ao listar instâncias: {}", e.getMessage());
            return List.of();
        }
    }

    // ── QR Code ──────────────────────────────────────────────

    /**
     * Retorna o QR Code do Redis (publicado pelo webhook QRCODE_UPDATED).
     * O frontend faz polling nesse endpoint a cada 3 segundos.
     */
    public String getQrCode(UUID connectionId) {
        Object qr = redisTemplate.opsForValue().get("qrcode:" + connectionId);
        if (qr != null) return qr.toString();

        // Fallback: busca direto no Evolution Go se não tiver no Redis ainda
        return evolutionAdapter.getQrCode(connectionId);
    }

    /**
     * Chamado pelo WebhookModule quando recebe QRCODE_UPDATED.
     * Salva no Redis com TTL de 60s e faz broadcast via WebSocket.
     */
    public void handleQrCodeUpdate(UUID connectionId, String base64) {
        redisTemplate.opsForValue().set(
                "qrcode:" + connectionId,
                base64,
                Duration.ofSeconds(60)
        );

        // Broadcast para o frontend que estiver na tela de configuração
        messagingTemplate.convertAndSend(
                "/topic/connection/" + connectionId + "/qrcode",
                Map.of("base64", base64, "connectionId", connectionId)
        );

        log.debug("[EVOLUTION] QR Code atualizado para conexão {}", connectionId);
    }

    /**
     * Chamado pelo WebhookModule quando recebe CONNECTION_UPDATE.
     */
    @Transactional
    public void handleConnectionUpdate(UUID connectionId, String state) {
        boolean connected = "open".equals(state);

        connectionRepo.findById(connectionId).ifPresent(conn -> {
            conn.setConnected(connected);
            if (connected) conn.setLastConnectedAt(Instant.now());
            connectionRepo.save(conn);

            // Notifica frontend — conexão pronta ou perdida
            messagingTemplate.convertAndSend(
                    "/topic/connection/" + connectionId + "/status",
                    Map.of("connected", connected, "state", state)
            );

            log.info("[EVOLUTION] Conexão {} → state: {} (connected: {})",
                    connectionId, state, connected);
        });
    }

    // ── Sync automático de status ─────────────────────────────

    /**
     * A cada 2 minutos, verifica o estado real de todas as conexões ativas.
     * Garante que o banco reflita o estado real mesmo se um webhook foi perdido.
     */
    @Scheduled(fixedDelay = 120_000)
    public void syncConnectionStatuses() {
        List<WhatsAppConnection> activeConnections =
                connectionRepo.findByTenantIdAndActive(null, true)
                        .stream()
                        .filter(c -> c.getProvider() == WhatsAppConnection.ConnectionProvider.EVOLUTION)
                        .toList();

        if (activeConnections.isEmpty()) return;

        log.debug("[EVOLUTION] Sincronizando status de {} conexões", activeConnections.size());

        activeConnections.forEach(conn -> {
            try {
                var status = evolutionAdapter.getStatus(conn.getId());
                if (conn.getConnected() != status.connected()) {
                    conn.setConnected(status.connected());
                    if (status.connected()) conn.setLastConnectedAt(Instant.now());
                    if (status.phoneNumber() != null) conn.setPhoneNumber(status.phoneNumber());
                    connectionRepo.save(conn);
                    log.info("[EVOLUTION] Status atualizado — conexão {}: connected={}",
                            conn.getId(), status.connected());
                }
            } catch (Exception e) {
                log.warn("[EVOLUTION] Falha ao sincronizar conexão {}: {}",
                        conn.getId(), e.getMessage());
            }
        });
    }

    // ── Desconectar e remover ─────────────────────────────────

    @Transactional
    public void deleteConnection(UUID connectionId) {
        evolutionAdapter.disconnect(connectionId);
        connectionRepo.findById(connectionId).ifPresent(conn -> {
            conn.setActive(false);
            conn.setConnected(false);
            connectionRepo.save(conn);
        });
        log.info("[EVOLUTION] Conexão {} removida", connectionId);
    }
}
