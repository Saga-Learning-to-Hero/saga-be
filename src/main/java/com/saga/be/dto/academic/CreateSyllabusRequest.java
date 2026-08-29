package com.saga.be.dto.academic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateSyllabusRequest(
		@Size(max = 64) String externalSyllabusId,
		@NotBlank @Size(max = 64) String versionLabel,
		@Size(max = 255) String titleEnglish,
		@Size(max = 255) String titleVietnamese,
		BigDecimal credits,
		@Size(max = 64) String level,
		String learningTeachingMethod,
		String timeAllocation,
		String prerequisites,
		String description,
		String studentDuties,
		String tools,
		String textbooks,
		String referenceMaterials,
		String gradingScale) {}
