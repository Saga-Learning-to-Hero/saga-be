package com.saga.be.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Append-only audit row as stored (JSON already redacted at write time).")
public record AdminAuditLogResponse(
		UUID id,
		LocalDateTime occurredAt,
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
		@Schema(description = "Parsed before_data JSON already redacted at write time") Object before,
		@Schema(description = "Parsed after_data JSON already redacted at write time") Object after,
		@Schema(description = "Parsed metadata_json already redacted at write time") Object metadata,
		String source,
		String requestId,
		String ipAddress,
		String userAgent) {}
