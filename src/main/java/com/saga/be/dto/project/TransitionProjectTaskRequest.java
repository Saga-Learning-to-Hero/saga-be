package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;

public record TransitionProjectTaskRequest(String transitionId, String targetStatusId) {}
