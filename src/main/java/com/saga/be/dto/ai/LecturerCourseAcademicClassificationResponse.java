package com.saga.be.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "One persisted Academic Classification row with its course context. Rows are history, not a"
		+ " single verdict per artifact: an AI proposal and the lecturer's correction of it are separate rows.")
public record LecturerCourseAcademicClassificationResponse(
		@Schema(description = "Same shape as the artifact-level Academic Classification response.")
				AiAcademicClassificationResponse classification,
		@Schema(description = "True only for the existing authoritative rule: an AI row the lecturer CONFIRMED, or a"
				+ " HUMAN (lecturer correction) row. PROPOSED, REJECTED and a CORRECTED original AI row are false.")
				boolean authoritative,
		UUID projectId,
		String projectName,
		@Schema(description = "Null when the project has no team.") UUID teamId,
		String teamName,
		@Schema(description = "TASK rows only; null for COMMIT rows or when the task is not in this project.")
				String taskExternalKey,
		@Schema(description = "TASK rows only.") String taskTitle,
		@Schema(description = "COMMIT rows only; null for TASK rows or when the commit is not in this project.")
				String commitSha,
		@Schema(description = "COMMIT rows only.") String commitMessage) {}
