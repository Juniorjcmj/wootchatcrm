package com.seucrm.api.connection;

import com.seucrm.config.TenantContext;
import com.seucrm.domain.conversation.WhatsAppConnection;
import com.seucrm.domain.conversation.WhatsAppConnectionRepository;
import com.seucrm.domain.user.User;
import com.seucrm.integration.whatsapp.EvolutionInstance;
import com.seucrm.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ── DTOs ────────────────────────────────────────────────────

record CreateEvolutionConnectionRequest(
        @NotBlank String name,         // nome amigável: "WhatsApp Vendas"
        @NotBlank String instanceName  // nome da instância no Evolution Go: "vendas-01"
) {}

record CreateWahaConnectionRequest(
        @NotBlank String name,          // nome amigável
        @NotBlank String baseUrl,       // URL do servidor WAHA
        @NotBlank String apiKey,        // X-Api-Key do WAHA
        @NotBlank String sessionName    // nome da sessão dentro do WAHA
) {}

record CreateWppConnectConnectionRequest(
        @NotBlank String name,
        @NotBlank String baseUrl,
        @NotBlank String secretKey,     // SECRET_KEY global do wppconnect-server
        @NotBlank String sessionName
) {}

record ConnectionDto(
        UUID    id,
        String  name,
        String  provider,
        String  phoneNumber,
        boolean connected,
        boolean active,
        Instant lastConnectedAt
) {
    static ConnectionDto from(WhatsAppConnection c) {
        return new ConnectionDto(
                c.getId(), c.getName(),
                c.getProvider().name(),
                c.getPhoneNumber(),
                Boolean.TRUE.equals(c.getConnected()),
                Boolean.TRUE.equals(c.getActive()),
                c.getLastConnectedAt()
        );
    }
}

// ── Controller ──────────────────────────────────────────────

@RestController
@RequestMapping("/v1/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final EvolutionConnectionService  evolutionService;
    private final WahaConnectionService       wahaService;
    private final WppConnectConnectionService wppService;
    private final WhatsAppConnectionRepository connectionRepo;

    // GET /v1/connections — lista conexões do tenant atual.
    // Para conexões EVOLUTION, consulta o estado ao vivo no Evolution Go
    // (best-effort, falha → devolve cache).
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<List<ConnectionDto>> list() {
        UUID tenantId = UUID.fromString(TenantContext.get());
        List<ConnectionDto> connections = connectionRepo
                .findByTenantIdAndActive(tenantId, true)
                .stream()
                .map(c -> switch (c.getProvider()) {
                    case EVOLUTION  -> evolutionService.refreshStatus(c.getId());
                    case WAHA       -> wahaService.refreshStatus(c.getId());
                    case WPPCONNECT -> wppService.refreshStatus(c.getId());
                    default         -> c;
                })
                .filter(java.util.Objects::nonNull)
                .map(ConnectionDto::from)
                .toList();
        return ResponseEntity.ok(connections);
    }

    // POST /v1/connections/waha — cria nova conexão WAHA
    @PostMapping("/waha")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConnectionDto> createWaha(
            @Valid @RequestBody CreateWahaConnectionRequest req,
            @AuthenticationPrincipal User user) {

        UUID tenantId = UUID.fromString(TenantContext.get());

        WhatsAppConnection connection = wahaService.createConnection(
                tenantId,
                req.name(),
                req.baseUrl(),
                req.apiKey(),
                req.sessionName().trim().toLowerCase().replaceAll("\\s+", "-"),
                user.getId()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionDto.from(connection));
    }

    // POST /v1/connections/wppconnect — cria nova conexão WPPConnect
    @PostMapping("/wppconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConnectionDto> createWppConnect(
            @Valid @RequestBody CreateWppConnectConnectionRequest req,
            @AuthenticationPrincipal User user) {

        UUID tenantId = UUID.fromString(TenantContext.get());

        WhatsAppConnection connection = wppService.createConnection(
                tenantId,
                req.name(),
                req.baseUrl(),
                req.secretKey(),
                req.sessionName().trim().toLowerCase().replaceAll("\\s+", "-"),
                user.getId()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionDto.from(connection));
    }

    // POST /v1/connections/evolution — cria nova conexão Evolution Go
    @PostMapping("/evolution")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConnectionDto> createEvolution(
            @Valid @RequestBody CreateEvolutionConnectionRequest req,
            @AuthenticationPrincipal User user) {

        UUID tenantId = UUID.fromString(TenantContext.get());

        WhatsAppConnection connection = evolutionService.createConnection(
                tenantId,
                req.name(),
                req.instanceName(),
                user.getId()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionDto.from(connection));
    }

    // GET /v1/connections/{id}/qrcode — retorna QR Code para escanear
    @GetMapping("/{id}/qrcode")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> getQrCode(@PathVariable UUID id) {
        WhatsAppConnection conn = findOwned(id);
        String base64 = switch (conn.getProvider()) {
            case WAHA       -> wahaService.getQrCode(id);
            case WPPCONNECT -> wppService.getQrCode(id);
            default         -> evolutionService.getQrCode(id);
        };
        if (base64 == null) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok(Map.of("base64", base64, "connectionId", id.toString()));
    }

    // GET /v1/connections/{id}/status — status atual da instância
    @GetMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable UUID id) {
        WhatsAppConnection initial = findOwned(id);
        WhatsAppConnection conn = switch (initial.getProvider()) {
            case WAHA       -> wahaService.refreshStatus(id);
            case WPPCONNECT -> wppService.refreshStatus(id);
            default         -> evolutionService.refreshStatus(id);
        };
        if (conn == null) conn = initial;

        return ResponseEntity.ok(Map.of(
                "connectionId", id,
                "name",         conn.getName(),
                "connected",    Boolean.TRUE.equals(conn.getConnected()),
                "phoneNumber",  conn.getPhoneNumber() != null ? conn.getPhoneNumber() : "",
                "lastConnectedAt", conn.getLastConnectedAt() != null
                        ? conn.getLastConnectedAt().toString() : ""
        ));
    }

    // POST /v1/connections/{id}/sync-contacts — importa contatos do WhatsApp como Leads
    @PostMapping("/{id}/sync-contacts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<java.util.Map<String, Integer>> syncContacts(@PathVariable UUID id) {
        validateOwnership(id);
        return ResponseEntity.ok(evolutionService.syncContactsFromEvolution(id));
    }

    // POST /v1/connections/{id}/disconnect — faz logout no provider mas mantém a conexão (active=true)
    @PostMapping("/{id}/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConnectionDto> disconnect(@PathVariable UUID id) {
        WhatsAppConnection conn = findOwned(id);
        switch (conn.getProvider()) {
            case WAHA       -> wahaService.disconnect(id);
            case WPPCONNECT -> wppService.disconnect(id);
            default         -> evolutionService.disconnect(id);
        }
        return ResponseEntity.ok(ConnectionDto.from(connectionRepo.findById(id).orElse(conn)));
    }

    // POST /v1/connections/{id}/resync-webhook
    // Re-aplica o webhook + subscribe da instância. Útil quando a URL do ngrok mudou,
    // ou quando a subscribe original estava com nomes errados.
    @PostMapping("/{id}/resync-webhook")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> resyncWebhook(@PathVariable UUID id) {
        validateOwnership(id);
        String webhookUrl = evolutionService.resyncWebhook(id);
        return ResponseEntity.ok(Map.of(
                "connectionId", id,
                "webhookUrl",   webhookUrl
        ));
    }

    // POST /v1/connections/{id}/reconnect
    // Limpa a instância zumbi no Evolution Go (delete + recreate com novo token)
    // e reconfigura o webhook. Usado quando o cliente do whatsmeow caiu e /instance/connect
    // não consegue mais reerguer a sessão sozinho.
    @PostMapping("/{id}/reconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConnectionDto> reconnect(@PathVariable UUID id) {
        WhatsAppConnection conn = findOwned(id);
        switch (conn.getProvider()) {
            case WAHA       -> wahaService.reconnect(id);
            case WPPCONNECT -> wppService.reconnect(id);
            default         -> evolutionService.reconnect(id);
        }
        return ResponseEntity.ok(ConnectionDto.from(connectionRepo.findById(id).orElse(conn)));
    }

    // DELETE /v1/connections/{id} — desconecta e desativa
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        validateOwnership(id);
        evolutionService.deleteConnection(id);
        return ResponseEntity.noContent().build();
    }

    // GET /v1/connections/evolution/instances — lista instâncias no servidor
    @GetMapping("/evolution/instances")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<EvolutionInstance>> listServerInstances() {
        return ResponseEntity.ok(evolutionService.listAvailableInstances());
    }

    // ── Helpers ─────────────────────────────────────────────

    private WhatsAppConnection findOwned(UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        return connectionRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> BusinessException.notFound("Connection", id));
    }

    private void validateOwnership(UUID id) {
        findOwned(id); // lança 404 se não pertencer ao tenant
    }
}
