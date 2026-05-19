package com.seucrm.api.lead;

import com.seucrm.domain.lead.Lead;
import com.seucrm.domain.lead.Tag;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record LeadResponse(
    UUID id,
    String name,
    String phone,
    String email,
    String channel,
    String origin,
    UUID assignedTo,
    short score,
    String temperature,
    Set<String> tags
) {
    static LeadResponse from(Lead l) {
        return new LeadResponse(
            l.getId(),
            l.getName(),
            l.getPhone(),
            l.getEmail(),
            l.getChannel().name(),
            l.getOrigin() != null ? l.getOrigin().name() : null,
            l.getAssignedTo(),
            l.getScore(),
            l.getTemperature(),
            l.getTags().stream().map(Tag::getName).collect(Collectors.toSet())
        );
    }
}
