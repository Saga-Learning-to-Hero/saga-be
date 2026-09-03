package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateStudentProjectRequest(
		@NotBlank @Size(max = 255) String name, UUID projectTypeId, String description) {}
