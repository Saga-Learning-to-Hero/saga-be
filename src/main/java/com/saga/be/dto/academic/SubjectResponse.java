package com.saga.be.dto.academic;

import com.saga.be.entity.enums.SubjectStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SubjectResponse(
		UUID id,
		String code,
		String nameEnglish,
		String nameVietnamese,
		SubjectStatus status,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		List<SyllabusSummaryResponse> syllabi) {}
