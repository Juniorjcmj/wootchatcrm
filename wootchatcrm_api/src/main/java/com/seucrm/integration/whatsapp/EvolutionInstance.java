package com.seucrm.integration.whatsapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Instância retornada pelo GET /instances do Evolution Go

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvolutionInstance(
        String instanceName,
        String instanceId,
        String status,           // open | close | connecting
        String phoneNumber,
        String profileName,
        String profilePicUrl
) {}
