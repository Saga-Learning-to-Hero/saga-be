package com.saga.be.dto.academic;

import com.saga.be.entity.enums.SyllabusStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SyllabusDetailResponse(
		UUID id,
		UUID subjectId,
		String subjectCode,
		String externalSyllabusId,
		String versionLabel,
		SyllabusStatus status,
		String titleEnglish,
		String titleVietnamese,
		BigDecimal credits,
		String level,
		String learningTeachingMethod,
		String timeAllocation,
		String prerequisites,
		String description,
		String studentDuties,
		String tools,
		String textbooks,
		String referenceMaterials,
		String gradingScale,
		LocalDateTime publishedAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		List<LearningOutcomeResponse> learningOutcomes,
		List<LearningUnitResponse> learningUnits,
		List<PhaseResponse> phases) {}
