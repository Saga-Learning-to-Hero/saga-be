package com.saga.be.dto.project;

import jakarta.validation.constraints.Size;

/** Move a projected task into a Jira sprint, or {@code sprintId=null} for backlog. */
public record PutProjectTaskSprintRequest(Long sprintId) {}
