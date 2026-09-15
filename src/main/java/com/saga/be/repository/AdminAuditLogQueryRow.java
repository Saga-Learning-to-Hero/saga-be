package com.saga.be.repository;

import com.saga.be.entity.enums.AuditSource;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminAuditLogQueryRow(
		UUID id,
		UUID actorUserId,
		String actorFullNameSnapshot,
		String actorRoleSnapshot,
		String actorEmailSnapshot,
		String actorStudentCodeSnapshot,
		UUID contextClassId,
		String contextClassCodeSnapshot,
		String contextClassNameSnapshot,
		UUID contextCourseId,
		UUID contextTeamId,
		UUID contextProjectId,
		String action,
		String entityType,
		UUID entityId,
		String beforeData,
		String afterData,
		String metadataJson,
		AuditSource source,
		String requestId,
		String ipAddress,
		String userAgent,
		LocalDateTime occurredAt) {}
