package com.saga.be.service.ai;

import com.saga.be.dto.ai.AiAcademicClassificationResponse;
import com.saga.be.dto.ai.LecturerCourseAcademicClassificationPageResponse;
import com.saga.be.dto.ai.LecturerCourseAcademicClassificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AiAcademicClassificationRepository;
import com.saga.be.repository.LecturerCourseAcademicClassificationRow;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-only, course-scoped read of persisted Academic Classification history. Never submits,
 * never calls saga-ai or a provider, never resolves course AI credentials.
 */
@Service
@Profile("!test")
public class LecturerCourseAcademicClassificationReadService {

	static final int DEFAULT_PAGE = 0;
	static final int DEFAULT_SIZE = 50;
	static final int MAX_SIZE = 200;

	private final AiAcademicClassificationRepository classifications;
	private final LecturerCourseAuthorization authorization;

	public LecturerCourseAcademicClassificationReadService(
			AiAcademicClassificationRepository classifications, LecturerCourseAuthorization authorization) {
		this.classifications = classifications;
		this.authorization = authorization;
	}

	@Transactional(readOnly = true)
	public LecturerCourseAcademicClassificationPageResponse list(
			UserAccount actor,
			UUID courseId,
			AiArtifactType artifactType,
			AiAcademicClassificationStatus status,
			UUID projectId,
			UUID teamId,
			Integer page,
			Integer size) {
		authorization.requireAssignedLecturerStrict(
				actor, courseId, "Only the assigned lecturer can view this course's academic classifications.");
		int pageNumber = page == null ? DEFAULT_PAGE : page;
		int pageSize = size == null ? DEFAULT_SIZE : size;
		if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_SIZE) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"page must be >= 0 and size must be between 1 and " + MAX_SIZE + ".");
		}
		Page<LecturerCourseAcademicClassificationRow> rows = classifications.findCoursePage(
				courseId, artifactType, status, projectId, teamId, PageRequest.of(pageNumber, pageSize));
		return new LecturerCourseAcademicClassificationPageResponse(
				rows.getContent().stream().map(LecturerCourseAcademicClassificationReadService::toResponse).toList(),
				pageNumber,
				pageSize,
				rows.getTotalElements());
	}

	/** Same rule as AiAcademicClassificationRepository#findAuthoritative / #findConfirmedExamples. */
	static boolean isAuthoritative(AiAcademicProvenance provenance, AiAcademicClassificationStatus status) {
		return (provenance == AiAcademicProvenance.AI && status == AiAcademicClassificationStatus.CONFIRMED)
				|| provenance == AiAcademicProvenance.HUMAN;
	}

	static LecturerCourseAcademicClassificationResponse toResponse(LecturerCourseAcademicClassificationRow row) {
		boolean phase = row.targetType() == AiAcademicTargetType.PHASE;
		AiAcademicClassificationResponse classification = new AiAcademicClassificationResponse(
				row.id(),
				name(row.artifactType()),
				row.artifactId(),
				row.artifactRevision(),
				row.syllabusVersionId(),
				name(row.targetType()),
				phase ? row.phaseId() : row.deliverableId(),
				phase ? row.phaseCode() : row.deliverableCode(),
				phase ? row.phaseName() : row.deliverableName(),
				row.confidence(),
				row.aiSummary(),
				name(row.status()),
				name(row.provenance()),
				row.sourceClassificationId(),
				row.reviewedAt(),
				row.createdAt());
		return new LecturerCourseAcademicClassificationResponse(
				classification,
				isAuthoritative(row.provenance(), row.status()),
				row.projectId(),
				row.projectName(),
				row.teamId(),
				row.teamName(),
				row.taskExternalKey(),
				row.taskTitle(),
				row.commitSha(),
				row.commitMessage());
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}
}
