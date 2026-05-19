package com.seucrm.api.lead;

import com.seucrm.domain.lead.LeadOrigin;
import java.util.UUID;

public record UpdateLeadRequest(
    String name,
    String phone,
    String email,
    String website,
    LeadOrigin origin,
    UUID assignedTo
) {}
