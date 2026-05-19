package com.seucrm.api.lead;

import com.seucrm.domain.lead.LeadChannel;
import com.seucrm.domain.lead.LeadOrigin;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record CreateLeadRequest(
    @NotBlank String name,
    String phone,
    String email,
    String website,
    String document,
    LocalDate birthdate,
    LeadChannel channel,
    LeadOrigin origin,
    UUID assignedTo,
    Set<UUID> tagIds
) {}
