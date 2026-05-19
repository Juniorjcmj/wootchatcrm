package com.seucrm.api.auth;

public record LoginResponse(
    String accessToken,
    String userId,
    String tenantId,
    String name,
    String email,
    String role,
    String avatarUrl
) {}
