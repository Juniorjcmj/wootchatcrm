package com.seucrm.domain.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);
    Optional<User> findByEmail(String email);
    Page<User> findByTenantIdAndActive(UUID tenantId, Boolean active, Pageable pageable);
    List<User> findByTenantIdAndRoleIn(UUID tenantId, List<UserRole> roles);

    @Query("SELECT u FROM User u WHERE u.tenantId = :tenantId AND u.role IN ('AGENT','SUPERVISOR') AND u.active = true")
    List<User> findActiveAgentsByTenant(UUID tenantId);
    boolean existsByTenantIdAndEmail(UUID tenantId, String email);
}
