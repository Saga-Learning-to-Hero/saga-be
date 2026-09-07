package com.saga.be.dto.academic;

import java.util.UUID;

public record LecturerDirectoryResponse(
		UUID lecturerProfileId,
		UUID userId,
		String fullName,
		String email,
		boolean active) {}
