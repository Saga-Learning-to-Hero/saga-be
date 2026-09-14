package com.saga.be.dto.task;

import java.util.List;
import java.util.UUID;

/**
 * Current user's work sessions on one task. {@code activeSession} is the earliest OPEN row (or
 * null). {@code sessions} is the full history including that active row and every STOPPED row;
 * completed history is never collapsed.
 */
public record TaskWorkSessionsResponse(
		UUID taskId, TaskWorkSessionResponse activeSession, List<TaskWorkSessionResponse> sessions) {}
