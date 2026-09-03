package com.saga.be.dto.project;

import java.util.UUID;

public record ProjectTypeResponse(UUID id, String code, String name, String description) {}
