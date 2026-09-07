package com.saga.be.dto.roster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Manual Admin add of one student to a course roster. Same identity rules as Excel import.")
public record AddRosterStudentRequest(
		@Schema(example = "Nguyễn Văn Ánh") @NotBlank @Size(max = 255) String fullName,
		@Schema(example = "SE123456") @NotBlank @Size(max = 64) String studentCode,
		@Schema(example = "student@gmail.com") @NotBlank @Email @Size(max = 255) String email) {}
