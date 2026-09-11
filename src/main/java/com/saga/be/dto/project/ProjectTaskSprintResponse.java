package com.saga.be.dto.project;

import java.util.UUID;

public record ProjectTaskSprintResponse(UUID id, String externalSprintId, String name, String state) {}
