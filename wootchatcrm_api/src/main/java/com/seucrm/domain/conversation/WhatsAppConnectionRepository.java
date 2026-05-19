package com.seucrm.domain.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppConnectionRepository extends JpaRepository<WhatsAppConnection, UUID> {
    List<WhatsAppConnection> findByTenantIdAndActive(UUID tenantId, Boolean active);
    Optional<WhatsAppConnection> findByIdAndTenantId(UUID id, UUID tenantId);
    List<WhatsAppConnection> findByTenantIdAndProvider(
        UUID tenantId, 
        WhatsAppConnection.ConnectionProvider provider
    );
}
