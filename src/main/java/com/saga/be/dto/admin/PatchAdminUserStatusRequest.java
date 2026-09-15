package com.saga.be.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Activate or deactivate a STUDENT or LECTURER account. Only ACTIVE and INACTIVE are accepted.")
public record PatchAdminUserStatusRequest(
		@NotBlank @Schema(example = "INACTIVE", description = "ACTIVE or INACTIVE") String status) {}
