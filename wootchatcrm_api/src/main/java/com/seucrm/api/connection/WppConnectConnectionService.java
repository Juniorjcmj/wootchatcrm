package com.seucrm.api.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seucrm.domain.conversation.WhatsAppConnection;
import com.seucrm.domain.conversation.WhatsAppConnectionRepository;
import com.seucrm.integration.whatsapp.WppConnectAdapter;
import com.seucrm.integration.whatsapp.WppConnectCredentials;
import com.seucrm.shared.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Serviço de gestão de conexões WPPConnect — espelha o WahaConnectionService.
 * Cada conexão guarda baseUrl + secretKey + session + sessionToken (criptografados).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WppConnectConnectionService {

    @Value("${app.whatsapp.webhook-base-url}")
    private String webhookBaseUrl;

    private final WhatsAppConnectionRepository connectionRepo;
    private final EncryptionService            encryptionService;
    private final WppConnectAdapter            wppAdapter;
    private final ObjectMapper                 objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate        messagingTemplate;

    @Transactional
    public WhatsAppConnection createConnection(
            UUID   tenantId,
            String displayName,
            String baseUrl,
            String secretKey,
            String sessionName,
            UUID   createdBy) {

        if (baseUrl == null || baseUrl.isBlank())            throw new RuntimeException("baseUrl é obrigatório");
        if (secretKey == null || secretKey.isBlank())        throw new RuntimeException("secretKey é obrigatória");
        if (sessionName == null || sessionName.isBlank())    throw new RuntimeException("sessionName é obrigatório");

        String normalizedUrl = baseUrl.replaceAll("/+$", "");
        log.info("[WPP] Criando conexão '{}' (session={}) → {}", displayName, sessionName, normalizedUrl);

        // 1. Gera o sessionToken via SECRET_KEY
        String token = wppAdapter.generateSessionToken(normalizedUrl, secretKey, sessionName);

        // 2. Monta credenciais e criptografa
        WppConnectCredentials credentials = new WppConnectCredentials(normalizedUrl, secretKey, sessionName, token);
        String credentialsJson;
        try {
            credentialsJson = objectMapper.writeValueAsString(credentials);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao serializar credenciais WPPConnect", e);
        }

        // 3. Salva a conexão
        WhatsAppConnection connection = WhatsAppConnection.builder()
                .tenantId(tenantId)
                .name(displayName)
                .provider(WhatsAppConnection.ConnectionProvider.WPPCONNECT)
                .credentialsEnc(encryptionService.encrypt(credentialsJson))
                .webhookToken(UUID.randomUUID().toString())
                .active(true)
                .connected(false)
                .createdBy(createdBy)
                .build();

        connection = connectionRepo.save(connection);
        UUID connectionId = connection.getId();

        // 4. Inicia a sessão com webhook
        String webhookUrl = webhookBaseUrl + "/v1/webhooks/wppconnect/" + connectionId;
        try {
            wppAdapter.startSession(connectionId, webhookUrl);
        } catch (Exception e) {
            log.warn("[WPP] Falha ao iniciar sessão (conexão salva — clique em Conectar para tentar de novo): {}", e.getMessage());
        }

        log.info("[WPP] Conexão {} criada. Webhook: {}", connectionId, webhookUrl);
        return connection;
    }

    // ── QR Code ──────────────────────────────────────────────

    public String getQrCode(UUID connectionId) {
        Object cached = redisTemplate.opsForValue().get("qrcode:" + connectionId);
        if (cached != null) return cached.toString();
        return wppAdapter.getQrCode(connectionId);
    }

    public void handleQrCodeUpdate(UUID connectionId, String base64) {
        redisTemplate.opsForValue().set("qrcode:" + connectionId, base64, Duration.ofSeconds(60));
        messagingTemplate.convertAndSend(
                "/topic/connection/" + connectionId + "/qrcode",
                Map.of("base64", base64, "connectionId", connectionId)
        );
        log.debug("[WPP] QR Code atualizado para conexão {}", connectionId);
    }

    // ── Status / lifecycle ───────────────────────────────────

    @Transactional
    public void handleConnectionUpdate(UUID connectionId, String state) {
        boolean connected = "CONNECTED".equalsIgnoreCase(state)
                         || "inChat".equalsIgnoreCase(state);

        connectionRepo.findById(connectionId).ifPresent(conn -> {
            conn.setConnected(connected);
            if (connected) conn.setLastConnectedAt(Instant.now());
            connectionRepo.save(conn);
        });

        messagingTemplate.convertAndSend(
                "/topic/connection/" + connectionId + "/status",
                Map.of("connected", connected, "state", state)
        );
    }

    public WhatsAppConnection refreshStatus(UUID connectionId) {
        return connectionRepo.findById(connectionId).map(conn -> {
            try {
                var status = wppAdapter.getStatus(connectionId);
                if (conn.getConnected() == null || conn.getConnected() != status.isConnected()) {
                    conn.setConnected(status.isConnected());
                    if (status.isConnected()) conn.setLastConnectedAt(Instant.now());
                    conn = connectionRepo.save(conn);
                }
            } catch (Exception e) {
                log.debug("[WPP] refreshStatus falhou para {}: {}", connectionId, e.getMessage());
            }
            return conn;
        }).orElse(null);
    }

    public void disconnect(UUID connectionId) {
        wppAdapter.disconnect(connectionId);
        connectionRepo.findById(connectionId).ifPresent(c -> {
            c.setConnected(false);
            connectionRepo.save(c);
        });
    }

    /**
     * Reseta a sessão (close + start novamente). Usado quando a sessão fica
     * em estado ruim e o /start-session não emite QR novo sozinho.
     */
    @Transactional
    public void reconnect(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));

        // Encerra a sessão antiga (idempotente)
        try { wppAdapter.closeSession(connectionId); } catch (Exception ignored) {}
        try { wppAdapter.disconnect(connectionId);    } catch (Exception ignored) {}

        try { redisTemplate.delete("qrcode:" + connectionId); } catch (Exception ignored) {}

        String webhookUrl = webhookBaseUrl + "/v1/webhooks/wppconnect/" + connectionId;
        wppAdapter.startSession(connectionId, webhookUrl);

        conn.setConnected(false);
        conn.setPhoneNumber(null);
        connectionRepo.save(conn);

        log.info("[WPP] Conexão {} resetada — aguardando QR", connectionId);
    }
}
