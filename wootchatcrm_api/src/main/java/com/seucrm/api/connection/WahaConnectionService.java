package com.seucrm.api.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seucrm.domain.conversation.WhatsAppConnection;
import com.seucrm.domain.conversation.WhatsAppConnectionRepository;
import com.seucrm.integration.whatsapp.WahaAdapter;
import com.seucrm.integration.whatsapp.WahaCredentials;
import com.seucrm.shared.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serviço de gestão de conexões WAHA — espelha o EvolutionConnectionService,
 * mas usa o adapter WAHA e armazena credenciais por conexão (cada cliente
 * pode ter sua própria instalação WAHA com URL/api-key próprios).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WahaConnectionService {

    @Value("${app.whatsapp.webhook-base-url}")
    private String webhookBaseUrl;

    private final WhatsAppConnectionRepository connectionRepo;
    private final EncryptionService            encryptionService;
    private final WahaAdapter                  wahaAdapter;
    private final ObjectMapper                 objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate        messagingTemplate;

    // ── Criar nova conexão WAHA ──────────────────────────────

    @Transactional
    public WhatsAppConnection createConnection(
            UUID   tenantId,
            String displayName,
            String baseUrl,
            String apiKey,
            String sessionName,
            UUID   createdBy) {

        if (baseUrl == null || baseUrl.isBlank())        throw new RuntimeException("baseUrl é obrigatório");
        if (sessionName == null || sessionName.isBlank()) throw new RuntimeException("sessionName é obrigatório");

        // Normaliza: tira barra final
        String normalizedUrl = baseUrl.replaceAll("/+$", "");

        log.info("[WAHA] Criando conexão '{}' (session={}) → {}", displayName, sessionName, normalizedUrl);

        WahaCredentials credentials = new WahaCredentials(normalizedUrl, apiKey, sessionName);

        String credentialsJson;
        try {
            credentialsJson = objectMapper.writeValueAsString(credentials);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao serializar credenciais WAHA", e);
        }

        WhatsAppConnection connection = WhatsAppConnection.builder()
                .tenantId(tenantId)
                .name(displayName)
                .provider(WhatsAppConnection.ConnectionProvider.WAHA)
                .credentialsEnc(encryptionService.encrypt(credentialsJson))
                .webhookToken(UUID.randomUUID().toString())
                .active(true)
                .connected(false)
                .createdBy(createdBy)
                .build();

        connection = connectionRepo.save(connection);
        UUID connectionId = connection.getId();

        // Inicia a sessão no WAHA com webhook apontando pro CRM
        String webhookUrl = webhookBaseUrl + "/v1/webhooks/waha/" + connectionId;
        try {
            wahaAdapter.startSession(connectionId, webhookUrl);
        } catch (Exception e) {
            log.warn("[WAHA] Falha ao iniciar sessão (a conexão foi salva — tente Conectar pela UI): {}", e.getMessage());
        }

        log.info("[WAHA] Conexão {} criada. Webhook: {}", connectionId, webhookUrl);
        return connection;
    }

    // ── QR Code ──────────────────────────────────────────────

    public String getQrCode(UUID connectionId) {
        Object cached = redisTemplate.opsForValue().get("qrcode:" + connectionId);
        if (cached != null) return cached.toString();
        return wahaAdapter.getQrCode(connectionId);
    }

    public void handleQrCodeUpdate(UUID connectionId, String base64) {
        redisTemplate.opsForValue().set("qrcode:" + connectionId, base64, Duration.ofSeconds(60));
        messagingTemplate.convertAndSend(
                "/topic/connection/" + connectionId + "/qrcode",
                Map.of("base64", base64, "connectionId", connectionId)
        );
        log.debug("[WAHA] QR Code atualizado para conexão {}", connectionId);
    }

    // ── Status / lifecycle ───────────────────────────────────

    @Transactional
    public void handleConnectionUpdate(UUID connectionId, String state) {
        // estados WAHA: STOPPED, STARTING, SCAN_QR_CODE, WORKING, FAILED
        boolean working = "WORKING".equalsIgnoreCase(state);

        connectionRepo.findById(connectionId).ifPresent(conn -> {
            conn.setConnected(working);
            if (working) conn.setLastConnectedAt(Instant.now());
            connectionRepo.save(conn);
        });

        messagingTemplate.convertAndSend(
                "/topic/connection/" + connectionId + "/status",
                Map.of("connected", working, "state", state)
        );
    }

    public WhatsAppConnection refreshStatus(UUID connectionId) {
        return connectionRepo.findById(connectionId).map(conn -> {
            try {
                var status = wahaAdapter.getStatus(connectionId);
                if (conn.getConnected() == null || conn.getConnected() != status.isConnected()) {
                    conn.setConnected(status.isConnected());
                    if (status.isConnected()) conn.setLastConnectedAt(Instant.now());
                    conn = connectionRepo.save(conn);
                }
            } catch (Exception e) {
                log.debug("[WAHA] refreshStatus falhou para {}: {}", connectionId, e.getMessage());
            }
            return conn;
        }).orElse(null);
    }

    public void disconnect(UUID connectionId) {
        wahaAdapter.disconnect(connectionId);
        connectionRepo.findById(connectionId).ifPresent(c -> {
            c.setConnected(false);
            connectionRepo.save(c);
        });
    }

    // ── Sync automático de status + self-healing de webhook ──

    /**
     * A cada 2 minutos, varre todas as conexões WAHA ativas:
     *   1. Atualiza o estado `connected` no banco a partir do gateway.
     *   2. Se a sessão está WORKING, re-aplica a config de webhook.
     *
     * Por que re-aplicar webhook? O WAHA pode perder a config (reinício do
     * container, fim de cache, etc.). Quando isso acontece, mensagens
     * chegam no WhatsApp mas o webhook nunca dispara — a CRM "só sincroniza
     * quando aberta", porque é o tráfego do frontend que acaba acordando
     * a sessão. Este job blindar contra esse cenário.
     */
    @Scheduled(fixedDelay = 120_000)
    public void syncConnectionStatuses() {
        List<WhatsAppConnection> activeConnections = connectionRepo.findByActive(true).stream()
                .filter(c -> c.getProvider() == WhatsAppConnection.ConnectionProvider.WAHA)
                .toList();

        if (activeConnections.isEmpty()) return;

        log.debug("[WAHA] Sincronizando status de {} conexões", activeConnections.size());

        activeConnections.forEach(conn -> {
            try {
                var status = wahaAdapter.getStatus(conn.getId());
                boolean nowConnected = status.isConnected();

                if (!Boolean.valueOf(nowConnected).equals(conn.getConnected())) {
                    conn.setConnected(nowConnected);
                    if (nowConnected) conn.setLastConnectedAt(Instant.now());
                    connectionRepo.save(conn);
                    log.info("[WAHA] Status atualizado — conexão {}: connected={}", conn.getId(), nowConnected);
                }

                if (nowConnected) {
                    String webhookUrl = webhookBaseUrl + "/v1/webhooks/waha/" + conn.getId();
                    wahaAdapter.updateWebhook(conn.getId(), webhookUrl);
                }
            } catch (Exception e) {
                log.warn("[WAHA] Falha ao sincronizar conexão {}: {}", conn.getId(), e.getMessage());
            }
        });
    }

    /**
     * Reseta a sessão no WAHA (delete + start) e limpa o QR cacheado.
     * Use quando a sessão está em estado ruim / sem QR.
     */
    @Transactional
    public void reconnect(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));

        // Apaga a sessão no WAHA (idempotente)
        wahaAdapter.deleteSession(connectionId);

        // Limpa QR antigo
        try { redisTemplate.delete("qrcode:" + connectionId); } catch (Exception ignored) {}

        // Recria a sessão com webhook do CRM
        String webhookUrl = webhookBaseUrl + "/v1/webhooks/waha/" + connectionId;
        wahaAdapter.startSession(connectionId, webhookUrl);

        conn.setConnected(false);
        conn.setPhoneNumber(null);
        connectionRepo.save(conn);

        log.info("[WAHA] Conexão {} resetada — aguardando QR", connectionId);
    }
}
