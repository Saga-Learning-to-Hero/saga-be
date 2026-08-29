package com.saga.be.dto.academic;

import java.util.UUID;

public record ActivityResponse(UUID id, String code, String name, String description, int orderIndex) {}
