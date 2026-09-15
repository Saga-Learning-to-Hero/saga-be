package com.saga.be.repository;

import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminUserQueryRow(
		UUID id,
		String email,
		String username,
		String fullName,
		String avatarUrl,
		AccountRole accountRole,
		AccountStatus accountStatus,
		String studentCode,
		UUID lecturerProfileId,
		LocalDateTime createdAt) {}
