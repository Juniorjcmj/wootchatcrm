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
    private final com.seucrm.domain.lead.LeadRepository leadRepository;

    // ── Criar nova instância no Evolution Go ─────────────────

    @Transactional
    public WhatsAppConnection createConnection(
            UUID   tenantId,
            String displayName,
            String instanceName,
            UUID   createdBy) {

        log.info("[EVOLUTION] Criando instância '{}' no Evolution Go para tenant {}",
                instanceName, tenantId);

        // 1. Gerar um token próprio para a instância (Evolution Go exige o caller forneça o token)
        String instanceApiKey = UUID.randomUUID().toString();

        // 2. Criar instância no Evolution Go
        // POST {base}/instance/create  body: { name, token }  → { data:{ id, name, token, ... }, message:"success" }
        JsonNode resp = webClient.post()
                .uri(evolutionBaseUrl + "/instance/create")
                .header("apikey", evolutionApiKey) // usa a chave GLOBAL para criar
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "name",  instanceName,
                        "token", instanceApiKey
                ))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (resp == null || !"success".equals(resp.path("message").asText(""))) {
            throw new RuntimeException("Evolution Go não retornou sucesso ao criar instância: " + resp);
        }

        // O Evolution Go ecoa o token na resposta — usa o que veio (defensivo)
        String returnedToken = resp.path("data").path("token").asText(instanceApiKey);
        if (returnedToken != null && !returnedToken.isBlank()) {
            instanceApiKey = returnedToken;
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
        // GET {base}/instance/all  → { "data": [ {id, name, token, jid, connected, ...}, ...], "message":"success" }
        try {
            JsonNode resp = webClient.get()
                    .uri(evolutionBaseUrl + "/instance/all")
                    .header("apikey", evolutionApiKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (resp == null || !resp.path("data").isArray()) return List.of();

            List<EvolutionInstance> out = new java.util.ArrayList<>();
            for (JsonNode n : resp.path("data")) {
                String jid    = n.path("jid").asText("");
                String phone  = jid.contains(":") ? jid.substring(0, jid.indexOf(":")) :
                                jid.contains("@") ? jid.substring(0, jid.indexOf("@")) : "";
                out.add(new EvolutionInstance(
                        n.path("name").asText(""),
                        n.path("id").asText(""),
                        n.path("connected").asBoolean(false) ? "open" : "close",
                        phone,
                        n.path("client_name").asText(""),
                        null
                ));
            }
            return out;

        } catch (Exception e) {
            log.error("[EVOLUTION] Falha ao listar instâncias: {}", e.getMessage());
            return List.of();
        }
    }

    // ── QR Code ──────────────────────────────────────────────

    /**
     * Recria a instância no Evolution Go (delete + create) e reconfigura o webhook.
     * Necessário quando a instância está em estado zumbi (logged out) e
     * o `/instance/connect` puro não dispara um novo QR.
     */
    @Transactional
    public void reconnect(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));

        EvolutionCredentials oldCreds;
        try {
            String json = encryptionService.decrypt(conn.getCredentialsEnc());
            oldCreds = objectMapper.readValue(json, EvolutionCredentials.class);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao ler credenciais da conexão", e);
        }
        String instanceName = oldCreds.instanceName();

        // 1. Limpa a instância antiga no Evolution Go (idempotente — ignora erros)
        try {
            webClient.delete()
                    .uri(oldCreds.baseUrl() + "/instance/delete")
                    .header("apikey", oldCreds.apiKey())
                    .retrieve().bodyToMono(Void.class).block();
            log.info("[EVOLUTION] Instância '{}' deletada no Evolution Go", instanceName);
        } catch (Exception e) {
            log.debug("[EVOLUTION] /instance/delete falhou (instância pode já não existir): {}", e.getMessage());
        }
        try {
            webClient.delete()
                    .uri(oldCreds.baseUrl() + "/instance/logout")
                    .header("apikey", oldCreds.apiKey())
                    .retrieve().bodyToMono(Void.class).block();
        } catch (Exception e) { /* ignore */ }

        // 2. Recria a instância com novo token
        String newToken = UUID.randomUUID().toString();
        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri(evolutionBaseUrl + "/instance/create")
                    .header("apikey", evolutionApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("name", instanceName, "token", newToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Evolution Go falhou ao recriar instância '" + instanceName + "': " + e.getMessage(), e);
        }
        if (resp == null || !"success".equals(resp.path("message").asText(""))) {
            throw new RuntimeException("Evolution Go não retornou sucesso ao recriar instância: " + resp);
        }

        String returnedToken = resp.path("data").path("token").asText(newToken);
        if (returnedToken != null && !returnedToken.isBlank()) {
            newToken = returnedToken;
        }
        log.info("[EVOLUTION] Instância '{}' recriada com novo token", instanceName);

        // 3. Atualiza credenciais no banco
        EvolutionCredentials newCreds = new EvolutionCredentials(evolutionBaseUrl, newToken, instanceName);
        try {
            String credJson = objectMapper.writeValueAsString(newCreds);
            conn.setCredentialsEnc(encryptionService.encrypt(credJson));
        } catch (Exception e) {
            throw new RuntimeException("Falha ao serializar credenciais", e);
        }
        conn.setConnected(false);
        conn.setPhoneNumber(null);
        connectionRepo.save(conn);

        // 4. Limpa QR antigo do Redis
        try { redisTemplate.delete("qrcode:" + connectionId); } catch (Exception ignored) {}

        // 5. Reconfigura webhook → Evolution Go gera o QR e dispara webhook qrcode.updated
        String webhookUrl = webhookBaseUrl + "/v1/webhooks/evolution/" + connectionId;
        evolutionAdapter.setupWebhook(connectionId, webhookUrl);

        log.info("[EVOLUTION] Conexão {} pronta para parear — aguardando QR", connectionId);
    }

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

    // ── Re-aplicar webhook (URL ngrok mudou, ou subscribe estava errado) ─

    public String resyncWebhook(UUID connectionId) {
        String webhookUrl = webhookBaseUrl + "/v1/webhooks/evolution/" + connectionId;
        evolutionAdapter.setupWebhook(connectionId, webhookUrl);
        log.info("[EVOLUTION] Webhook re-sincronizado para conexão {}: {}", connectionId, webhookUrl);
        return webhookUrl;
    }

    // ── Sync sob demanda (chamado pelo controller no /status) ─

    /**
     * Consulta o Evolution Go ao vivo e atualiza o banco se mudou.
     * Best-effort: se a chamada falhar, devolve o estado cacheado.
     */
    @Transactional
    public WhatsAppConnection refreshStatus(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId).orElse(null);
        if (conn == null) return null;
        if (conn.getProvider() != WhatsAppConnection.ConnectionProvider.EVOLUTION) return conn;

        try {
            var live = evolutionAdapter.getStatus(connectionId);
            boolean nowConnected = live.isConnected();
            if (!Boolean.valueOf(nowConnected).equals(conn.getConnected())) {
                conn.setConnected(nowConnected);
                if (nowConnected) conn.setLastConnectedAt(Instant.now());
                if (live.getPhoneNumber() != null) conn.setPhoneNumber(live.getPhoneNumber());
                conn = connectionRepo.save(conn);
                log.info("[EVOLUTION] refreshStatus — conexão {}: connected={}",
                        connectionId, nowConnected);
            }
        } catch (Exception e) {
            log.warn("[EVOLUTION] refreshStatus falhou para {}: {}", connectionId, e.getMessage());
        }
        return conn;
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
                boolean nowConnected = status.isConnected();
                if (!Boolean.valueOf(nowConnected).equals(conn.getConnected())) {
                    conn.setConnected(nowConnected);
                    if (nowConnected) conn.setLastConnectedAt(Instant.now());
                    if (status.getPhoneNumber() != null) conn.setPhoneNumber(status.getPhoneNumber());
                    connectionRepo.save(conn);
                    log.info("[EVOLUTION] Status atualizado — conexão {}: connected={}",
                            conn.getId(), nowConnected);
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

    /** Desconecta (logout no WhatsApp) MAS mantém a conexão no banco — pode reconectar via QR. */
    @Transactional
    public void disconnect(UUID connectionId) {
        evolutionAdapter.disconnect(connectionId);
        connectionRepo.findById(connectionId).ifPresent(conn -> {
            conn.setConnected(false);
            connectionRepo.save(conn);
        });
        log.info("[EVOLUTION] Conexão {} desconectada (logout, mantém ativa)", connectionId);
    }

    /**
     * Sincroniza a lista de contatos do WhatsApp (via GET /user/contacts do Evolution Go)
     * para a tabela de Leads. Cria leads novos quando não existem; atualiza apenas o nome
     * de leads que estão sem nome (não sobrescreve nomes editados manualmente).
     *
     * Retorna estatísticas: {imported, updated, skipped, total}.
     */
    @Transactional
    public java.util.Map<String, Integer> syncContactsFromEvolution(UUID connectionId) {
        WhatsAppConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));
        if (conn.getProvider() != WhatsAppConnection.ConnectionProvider.EVOLUTION) {
            throw new RuntimeException("Sync de contatos só para conexões EVOLUTION");
        }

        // Decodifica credenciais para chamar o Evolution Go diretamente
        com.seucrm.integration.whatsapp.EvolutionCredentials creds;
        try {
            String json = encryptionService.decrypt(conn.getCredentialsEnc());
            creds = objectMapper.readValue(json, com.seucrm.integration.whatsapp.EvolutionCredentials.class);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao ler credenciais", e);
        }

        com.fasterxml.jackson.databind.JsonNode resp;
        try {
            resp = webClient.get()
                    .uri(creds.baseUrl() + "/user/contacts")
                    .header("apikey", creds.apiKey())
                    .retrieve()
                    .bodyToMono(com.fasterxml.jackson.databind.JsonNode.class)
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao buscar contatos do Evolution Go", e);
        }

        if (resp == null || !resp.path("data").isArray()) {
            log.warn("[EVOLUTION] Resposta inesperada de /user/contacts: {}", resp);
            return java.util.Map.of("total", 0, "imported", 0, "updated", 0, "skipped", 0);
        }

        UUID tenantId = conn.getTenantId();
        int imported = 0, updated = 0, skipped = 0, total = 0;

        for (com.fasterxml.jackson.databind.JsonNode c : resp.path("data")) {
            total++;
            String jid = c.path("Jid").asText("");
            if (jid.isEmpty() || jid.endsWith("@g.us") || jid.endsWith("@newsletter")) {
                skipped++; continue; // ignora grupos e newsletters aqui
            }
            String phone = jid.replaceAll("@.*$", "").replaceAll("[^0-9]", "");
            if (phone.isEmpty()) { skipped++; continue; }

            String fullName     = c.path("FullName").asText("");
            String firstName    = c.path("FirstName").asText("");
            String businessName = c.path("BusinessName").asText("");
            String pushName     = c.path("PushName").asText("");
            String name = !fullName.isBlank()    ? fullName
                        : !firstName.isBlank()   ? firstName
                        : !businessName.isBlank()? businessName
                        : !pushName.isBlank()    ? pushName
                        : phone;

            var existing = leadRepository.findByTenantIdAndPhone(tenantId, phone);
            if (existing.isPresent()) {
                com.seucrm.domain.lead.Lead lead = existing.get();
                // Só atualiza o nome se o atual estiver vazio ou for o próprio phone (default)
                if (lead.getName() == null || lead.getName().isBlank() || lead.getName().equals(phone)) {
                    lead.setName(name);
                    leadRepository.save(lead);
                    updated++;
                } else {
                    skipped++;
                }
            } else {
                leadRepository.save(com.seucrm.domain.lead.Lead.builder()
                        .tenantId(tenantId)
                        .name(name)
                        .phone(phone)
                        .channel(com.seucrm.domain.lead.LeadChannel.WHATSAPP)
                        .externalId(phone)
                        .build());
                imported++;
            }
        }

        log.info("[EVOLUTION] Sync contatos {} → total={} imported={} updated={} skipped={}",
                connectionId, total, imported, updated, skipped);
        return java.util.Map.of("total", total, "imported", imported, "updated", updated, "skipped", skipped);
    }
}
