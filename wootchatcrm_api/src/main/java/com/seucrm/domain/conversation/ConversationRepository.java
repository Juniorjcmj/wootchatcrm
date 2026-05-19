package com.seucrm.domain.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Conversation> findByTenantIdAndExternalChatId(UUID tenantId, String externalChatId);

    // (`:status IS NULL OR ...`) não funciona quando o driver do Postgres é um enum nativo
    // — não consegue inferir o tipo do `null`. Convertemos o enum pra String aqui e
    // comparamos por string (Hibernate gera o cast adequado no SQL).
    @Query("""
        SELECT c FROM Conversation c
        WHERE c.tenantId = :tenantId
          AND (:statusStr IS NULL OR CAST(c.status AS string) = :statusStr)
          AND (:assignedTo IS NULL OR c.assignedTo = :assignedTo)
          AND (:connId IS NULL OR c.connectionId = :connId)
        ORDER BY c.lastMessageAt DESC NULLS LAST
    """)
    Page<Conversation> findWithFilters(
        @Param("tenantId") UUID tenantId,
        @Param("statusStr") String statusStr,
        @Param("assignedTo") UUID assignedTo,
        @Param("connId") UUID connId,
        Pageable pageable
    );

    default Page<Conversation> findWithFilters(UUID tenantId, ConversationStatus status,
                                               UUID assignedTo, UUID connId, Pageable pageable) {
        return findWithFilters(tenantId, status != null ? status.name() : null, assignedTo, connId, pageable);
    }

    @Query("SELECT SUM(c.unreadCount) FROM Conversation c WHERE c.tenantId = :tenantId AND c.assignedTo = :userId")
    Long totalUnreadByAgent(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
