package com.saga.be.repository;

import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiArtifactType;
import java.time.LocalDateTime;
import java.util.UUID;

/** Flat course-scoped projection of one ai_academic_classification row plus persisted context. */
public record LecturerCourseAcademicClassificationRow(
		UUID id,
		AiArtifactType artifactType,
		UUID artifactId,
		String artifactRevision,
		UUID syllabusVersionId,
		AiAcademicTargetType targetType,
		UUID phaseId,
		String phaseCode,
		String phaseName,
		UUID deliverableId,
		String deliverableCode,
		String deliverableName,
		Double confidence,
		String aiSummary,
		AiAcademicClassificationStatus status,
		AiAcademicProvenance provenance,
		UUID sourceClassificationId,
		LocalDateTime reviewedAt,
		LocalDateTime createdAt,
		UUID projectId,
		String projectName,
		UUID teamId,
		String teamName,
		String taskExternalKey,
		String taskTitle,
		String commitSha,
		String commitMessage) {}
