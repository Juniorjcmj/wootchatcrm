package com.seucrm.domain.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, UUID> {
    List<Tag> findByTenantId(UUID tenantId);
    boolean existsByTenantIdAndName(UUID tenantId, String name);
    Optional<Tag> findByTenantIdAndName(UUID tenantId, String name);
}
