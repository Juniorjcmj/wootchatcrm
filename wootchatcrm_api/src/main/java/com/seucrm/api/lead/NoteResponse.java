package com.seucrm.api.lead;

import com.seucrm.domain.lead.LeadNote;

import java.time.Instant;
import java.util.UUID;

public record NoteResponse(UUID id, UUID userId, String content, Instant createdAt) {
    public static NoteResponse from(LeadNote n) {
        return new NoteResponse(n.getId(), n.getUserId(), n.getContent(), n.getCreatedAt());
    }
}
