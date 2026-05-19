package com.seucrm.domain.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeadNoteRepository extends JpaRepository<LeadNote, UUID> {
    List<LeadNote> findByLeadIdOrderByCreatedAtDesc(UUID leadId);
}
