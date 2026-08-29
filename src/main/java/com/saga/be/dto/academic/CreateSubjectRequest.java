package com.saga.be.dto.academic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSubjectRequest(
		@NotBlank @Size(max = 64) String code,
		@NotBlank @Size(max = 255) String nameEnglish,
		@Size(max = 255) String nameVietnamese) {}
