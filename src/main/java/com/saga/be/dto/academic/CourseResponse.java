package com.saga.be.dto.academic;

import com.saga.be.entity.enums.SyllabusStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record CourseResponse(
		UUID id,
		String courseCode,
		String name,
		UUID academicClassId,
		String classCode,
		String className,
		UUID semesterId,
		String semesterCode,
		String semesterName,
		UUID subjectId,
		String subjectCode,
		String subjectName,
		UUID syllabusVersionId,
		String syllabusVersionLabel,
		SyllabusStatus syllabusStatus,
		UUID lecturerId,
		UUID lecturerUserId,
		String lecturerEmail,
		String lecturerFullName,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
