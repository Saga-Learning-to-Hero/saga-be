package com.saga.be.dto.academic;

import com.saga.be.entity.enums.SyllabusStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SyllabusSummaryResponse(
		UUID id,
		UUID subjectId,
		String externalSyllabusId,
		String versionLabel,
		SyllabusStatus status,
		String titleEnglish,
		String titleVietnamese,
		BigDecimal credits,
		LocalDateTime publishedAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
