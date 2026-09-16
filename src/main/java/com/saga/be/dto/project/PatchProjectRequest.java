package com.saga.be.dto.project;

import jakarta.validation.constraints.Size;

public record PatchProjectRequest(@Size(max = 255) String name, String description) {}
