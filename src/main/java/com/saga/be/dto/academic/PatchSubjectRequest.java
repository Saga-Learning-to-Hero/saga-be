package com.saga.be.dto.academic;

import com.saga.be.entity.enums.SubjectStatus;
import jakarta.validation.constraints.Size;

public record PatchSubjectRequest(
		@Size(max = 64) String code,
		@Size(max = 255) String nameEnglish,
		@Size(max = 255) String nameVietnamese,
		SubjectStatus status) {}
