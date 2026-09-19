package com.saga.be.repository;

import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.TaskStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/** Lightweight DONE / zero-V23-evidence row for student-dashboard MSR classification. */
public record StudentDashboardAnomalyCandidateRow(
		UUID id,
		String externalKey,
		String title,
		TaskStatus status,
		Priority priority,
		Integer storyPoint,
		LocalDateTime dueDate,
		String labelsJson) {}
