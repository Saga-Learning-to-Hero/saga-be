package com.saga.be.dto.roster;

import java.util.List;
import java.util.UUID;

public record CourseRosterResponse(
		UUID courseId,
		String classCode,
		String semesterCode,
		String subjectCode,
		int enrolledCount,
		int pendingInvitationCount,
		List<CourseRosterEntryResponse> entries) {}
