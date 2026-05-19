package com.seucrm.domain.lead;

import com.querydsl.core.types.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Optional;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID>, QuerydslPredicateExecutor<Lead> {
    Optional<Lead> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<Lead> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Lead> findAll(Predicate predicate, Pageable pageable);
    Optional<Lead> findByTenantIdAndPhone(UUID tenantId, String phone);
    Optional<Lead> findByTenantIdAndExternalId(UUID tenantId, String externalId);
    boolean existsByTenantIdAndPhone(UUID tenantId, String phone);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.tenantId = :tenantId AND l.active = true")
    long countActiveByTenant(UUID tenantId);
}
