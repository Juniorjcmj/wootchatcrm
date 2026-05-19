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

    private final EvolutionConnectionService evolutionService;
    private final WhatsAppConnectionRepository connectionRepo;

    // GET /v1/connections — lista conexões do tenant atual
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<List<ConnectionDto>> list() {
        UUID tenantId = UUID.fromString(TenantContext.get());
        List<ConnectionDto> connections = connectionRepo
                .findByTenantIdAndActive(tenantId, true)
                .stream()
                .map(ConnectionDto::from)
                .toList();
        return ResponseEntity.ok(connections);
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
        validateOwnership(id);

        String base64 = evolutionService.getQrCode(id);
        if (base64 == null) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok(Map.of("base64", base64, "connectionId", id.toString()));
    }

    // GET /v1/connections/{id}/status — status atual da instância
    @GetMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable UUID id) {
        WhatsAppConnection conn = findOwned(id);
        return ResponseEntity.ok(Map.of(
                "connectionId", id,
                "name",         conn.getName(),
                "connected",    Boolean.TRUE.equals(conn.getConnected()),
                "phoneNumber",  conn.getPhoneNumber() != null ? conn.getPhoneNumber() : "",
                "lastConnectedAt", conn.getLastConnectedAt() != null
                        ? conn.getLastConnectedAt().toString() : ""
        ));
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
