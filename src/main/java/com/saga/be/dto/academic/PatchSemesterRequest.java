package com.saga.be.dto.academic;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record PatchSemesterRequest(
		@Size(max = 64) String code,
		@Size(max = 255) String name,
		LocalDate startDate,
		LocalDate endDate) {}
