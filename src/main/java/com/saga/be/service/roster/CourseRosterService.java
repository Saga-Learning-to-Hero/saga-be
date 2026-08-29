package com.saga.be.service.roster;

import com.saga.be.auth.InstitutionalEmailPolicy;
import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.roster.CourseRosterEntryResponse;
import com.saga.be.dto.roster.CourseRosterResponse;
import com.saga.be.dto.roster.RosterConfirmResponse;
import com.saga.be.dto.roster.RosterPreviewResponse;
import com.saga.be.dto.roster.RosterPreviewRow;
import com.saga.be.dto.roster.RosterPreviewSummary;
import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RosterRowAction;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.entity.enums.StudentInvitationType;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.mail.template.EmailTemplateModel;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.mail.EmailOutboxService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class CourseRosterService {

	public static final String COURSE_ROSTER_IMPORTED = "COURSE_ROSTER_IMPORTED";
	public static final String COURSE_ENROLLMENT_CREATED = "COURSE_ENROLLMENT_CREATED";
	public static final String COURSE_ENROLLMENT_REACTIVATED = "COURSE_ENROLLMENT_REACTIVATED";
	public static final String COURSE_INVITATION_CREATED = "COURSE_INVITATION_CREATED";

	private final CourseRosterStore store;
	private final RosterPreviewStore previews;
	private final RosterProperties rosterProperties;
	private final InstitutionalEmailPolicy institutionalEmails;
	private final EmailOutboxService emails;
	private final EmailTemplateService templates;
	private final AuditService audit;
	private final PlatformTransactionManager transactionManager;
	private final SecureRandom random = new SecureRandom();

	public CourseRosterService(
			CourseRosterStore store,
			RosterPreviewStore previews,
			RosterProperties rosterProperties,
			AuthProperties authProperties,
			InstitutionalEmailPolicy institutionalEmails,
			EmailOutboxService emails,
			AuditService audit) {
		this(
				store,
				previews,
				rosterProperties,
				institutionalEmails,
				emails,
				new EmailTemplateService(authProperties),
				audit,
				null);
	}

	@Autowired
	public CourseRosterService(
			CourseRosterStore store,
			RosterPreviewStore previews,
			RosterProperties rosterProperties,
			AuthProperties authProperties,
			InstitutionalEmailPolicy institutionalEmails,
			EmailOutboxService emails,
			AuditService audit,
			EmailTemplateService templates,
			PlatformTransactionManager transactionManager) {
		this(
				store,
				previews,
				rosterProperties,
				institutionalEmails,
				emails,
				templates == null ? new EmailTemplateService(authProperties) : templates,
				audit,
				transactionManager);
	}

	CourseRosterService(
			CourseRosterStore store,
			RosterPreviewStore previews,
			RosterProperties rosterProperties,
			InstitutionalEmailPolicy institutionalEmails,
			EmailOutboxService emails,
			EmailTemplateService templates,
			AuditService audit,
			PlatformTransactionManager transactionManager) {
		this.store = store;
		this.previews = previews;
		this.rosterProperties = rosterProperties;
		this.institutionalEmails = institutionalEmails;
		this.emails = emails;
		this.templates = templates;
		this.audit = audit;
		this.transactionManager = transactionManager;
	}

	@Transactional(readOnly = true)
	public byte[] template(UUID courseId) {
		Course course = requireCourse(courseId);
		return CourseRosterWorkbook.template(course.getAcademicClass().getClassCode());
	}

	@Transactional(readOnly = true)
	public RosterPreviewResponse preview(UUID courseId, byte[] file, UserAccount admin) {
		Course course = requireCourse(courseId);
		if (file == null || file.length == 0) {
			throw new AcademicException(AcademicErrorCode.ROSTER_FILE_INVALID, HttpStatus.BAD_REQUEST, "Roster file is required.");
		}
		if (file.length > rosterProperties.getMaxFileBytes()) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_FILE_TOO_LARGE, HttpStatus.BAD_REQUEST, "Roster file is too large.");
		}
		List<CourseRosterWorkbook.RawRow> rawRows = CourseRosterWorkbook.parse(file);
		List<RosterPreviewRow> rows = classify(course, rawRows);
		String token = newToken();
		previews.save(
				token,
				new RosterPreviewSnapshot(admin.getId(), course.getId(), course.getAcademicClass().getClassCode(), rows),
				rosterProperties.getPreviewTtl());
		return new RosterPreviewResponse(
				token, course.getId(), course.getAcademicClass().getClassCode(), summary(rows), rows);
	}

	public RosterConfirmResponse confirm(UUID courseId, String previewToken, UserAccount admin, AuditRequest auditRequest) {
		if (!StringUtils.hasText(previewToken)) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_PREVIEW_INVALID, HttpStatus.BAD_REQUEST, "Preview token is required.");
		}
		Course course = requireCourse(courseId);
		String token = previewToken.trim();
		RosterPreviewSnapshot snapshot = previews
				.find(token)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.ROSTER_PREVIEW_EXPIRED, HttpStatus.BAD_REQUEST, "Roster preview expired or was not found."));
		if (!snapshot.adminUserId().equals(admin.getId()) || !snapshot.courseId().equals(course.getId())) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_PREVIEW_MISMATCH, HttpStatus.FORBIDDEN, "Roster preview does not match this admin or course.");
		}
		if (hasBlockingErrors(snapshot.rows())) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED,
					HttpStatus.CONFLICT,
					"Roster preview contains blocking errors.");
		}
		RosterConfirmResponse result = writeAtomic(() -> applyConfirm(requireCourse(courseId), snapshot.rows(), admin, auditRequest));
		previews.delete(token);
		return result;
	}

	private RosterConfirmResponse applyConfirm(
			Course course, List<RosterPreviewRow> rows, UserAccount admin, AuditRequest auditRequest) {
		int enrolled = 0;
		int invited = 0;
		int unchanged = 0;
		int emailsEnqueued = 0;
		for (RosterPreviewRow row : rows) {
			ApplyResult result = applyRow(course, row);
			enrolled += result.enrolledCount();
			invited += result.invitedCount();
			unchanged += result.unchangedCount();
			emailsEnqueued += result.emailCount();
		}
		audit.record(
				admin,
				null,
				null,
				COURSE_ROSTER_IMPORTED,
				"course",
				course.getId(),
				null,
				Map.of("classCode", course.getAcademicClass().getClassCode()),
				Map.of("enrolled", enrolled, "invited", invited, "unchanged", unchanged, "emailsEnqueued", emailsEnqueued),
				AuditSource.API,
				auditRequest == null ? null : auditRequest.requestId(),
				auditRequest == null ? null : auditRequest.ip(),
				auditRequest == null ? null : auditRequest.userAgent());
		return new RosterConfirmResponse(course.getId(), enrolled, invited, unchanged, emailsEnqueued, LocalDateTime.now());
	}

	@Transactional(readOnly = true)
	public CourseRosterResponse getRoster(UUID courseId) {
		Course course = requireCourse(courseId);
		List<CourseRosterEntryResponse> entries = new ArrayList<>();
		for (CourseEnrollment enrollment : store.listEnrollments(courseId)) {
			StudentProfile profile = enrollment.getStudentProfile();
			UserAccount user = profile == null ? null : profile.getUserAccount();
			entries.add(new CourseRosterEntryResponse(
					"ENROLLMENT",
					enrollment.getId(),
					null,
					user == null ? null : user.getId(),
					sanitize(profile == null ? null : profile.getStudentCode()),
					sanitize(user == null ? null : user.getFullName()),
					sanitize(user == null ? null : user.getEmail()),
					enrollment.getEnrollmentStatus() == null ? null : enrollment.getEnrollmentStatus().name(),
					null,
					"REGISTERED"));
		}
		for (StudentCourseInvitation invitation : store.listInvitations(courseId)) {
			if (invitation.getInvitationStatus() == null || !invitation.getInvitationStatus().isOutstanding()) {
				continue;
			}
			entries.add(new CourseRosterEntryResponse(
					"INVITATION",
					null,
					invitation.getId(),
					null,
					sanitize(invitation.getStudentCode()),
					sanitize(invitation.getFullName()),
					sanitize(invitation.getEmail()),
					null,
					invitation.getInvitationStatus().name(),
					"NOT_REGISTERED"));
		}
		long enrolled = entries.stream().filter(row -> "ENROLLMENT".equals(row.kind())).count();
		long pending = entries.stream().filter(row -> "INVITATION".equals(row.kind())).count();
		return new CourseRosterResponse(
				course.getId(),
				course.getAcademicClass().getClassCode(),
				course.getSemester().getCode(),
				course.getSubject().getSubjectCode(),
				(int) enrolled,
				(int) pending,
				entries);
	}

	private List<RosterPreviewRow> classify(Course course, List<CourseRosterWorkbook.RawRow> rawRows) {
		String expectedClass = normalizeCode(course.getAcademicClass().getClassCode());
		Map<String, Integer> emails = new LinkedHashMap<>();
		Map<String, Integer> codes = new LinkedHashMap<>();
		List<RosterPreviewRow> rows = new ArrayList<>();
		for (CourseRosterWorkbook.RawRow raw : rawRows) {
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();
			String classCode = normalizeCode(raw.classCode());
			String fullName = trimToNull(raw.fullName());
			String studentCode = normalizeCode(raw.studentCode());
			String email = normalizeEmail(raw.email());
			String memberCode = trimToNull(raw.memberCode());
			if (!StringUtils.hasText(classCode)) {
				errors.add("Class is required.");
			} else if (!classCode.equals(expectedClass)) {
				errors.add("Class does not match this course.");
			}
			if (!StringUtils.hasText(fullName)) {
				errors.add("FullName is required.");
			}
			if (!StringUtils.hasText(studentCode)) {
				errors.add("StudentCode is required.");
			}
			if (!StringUtils.hasText(email) || !email.contains("@")) {
				errors.add("Email is required.");
			}
			if (StringUtils.hasText(email) && emails.containsKey(email)) {
				errors.add("Duplicate email in workbook.");
			}
			if (StringUtils.hasText(studentCode) && codes.containsKey(studentCode)) {
				errors.add("Duplicate StudentCode in workbook.");
			}
			if (StringUtils.hasText(email)) {
				emails.put(email, raw.rowNumber());
			}
			if (StringUtils.hasText(studentCode)) {
				codes.put(studentCode, raw.rowNumber());
			}
			RosterRowAction action = RosterRowAction.INVALID;
			if (errors.isEmpty()) {
				action = classifyIdentity(course, email, studentCode, errors, warnings);
			}
			rows.add(new RosterPreviewRow(
					raw.rowNumber(),
					sanitize(classCode),
					sanitize(fullName),
					sanitize(studentCode),
					sanitize(email),
					sanitize(memberCode),
					action,
					List.copyOf(errors),
					List.copyOf(warnings)));
		}
		return rows;
	}

	private RosterRowAction classifyIdentity(
			Course course, String email, String studentCode, List<String> errors, List<String> warnings) {
		UserAccount account = store.findUserByEmail(email).orElse(null);
		StudentProfile byCode = store.findStudentByCode(studentCode).orElse(null);
		if (account != null && account.getAccountRole() != AccountRole.STUDENT) {
			errors.add("Existing account is not a Student.");
			return RosterRowAction.CONFLICT;
		}
		if (byCode != null) {
			UserAccount codeOwner = byCode.getUserAccount();
			if (codeOwner != null && !email.equalsIgnoreCase(codeOwner.getEmail())) {
				errors.add("StudentCode already belongs to another account.");
				return RosterRowAction.CONFLICT;
			}
		}
		if (account != null) {
			StudentProfile profile = store.findStudentByUserId(account.getId()).orElse(null);
			if (profile == null) {
				errors.add("Student profile is missing.");
				return RosterRowAction.CONFLICT;
			}
			if (StringUtils.hasText(profile.getStudentCode())
					&& !profile.getStudentCode().equalsIgnoreCase(studentCode)) {
				errors.add("StudentCode does not match the existing Student profile.");
				return RosterRowAction.CONFLICT;
			}
			CourseEnrollment enrollment = store.findEnrollment(profile.getId(), course.getId()).orElse(null);
			if (enrollment != null && enrollment.getEnrollmentStatus() == EnrollmentStatus.ACTIVE) {
				return RosterRowAction.ALREADY_ENROLLED;
			}
			return RosterRowAction.READY_ENROLL;
		}
		StudentCourseInvitation byEmail = store.findInvitationByCourseAndEmail(course.getId(), email).orElse(null);
		StudentCourseInvitation byStudentCode =
				store.findInvitationByCourseAndStudentCode(course.getId(), studentCode).orElse(null);
		if (byEmail != null && byStudentCode != null && !byEmail.getId().equals(byStudentCode.getId())) {
			errors.add("Email and StudentCode match different invitations.");
			return RosterRowAction.CONFLICT;
		}
		if (byStudentCode != null && StringUtils.hasText(byStudentCode.getEmail())
				&& !email.equalsIgnoreCase(byStudentCode.getEmail())) {
			errors.add("StudentCode already invited with a different email.");
			return RosterRowAction.CONFLICT;
		}
		StudentCourseInvitation existing = byEmail != null ? byEmail : byStudentCode;
		if (existing != null && isOutstandingInvitation(existing.getInvitationStatus())) {
			return RosterRowAction.ALREADY_INVITED;
		}
		if (existing != null && existing.getInvitationStatus() == StudentInvitationStatus.CLAIMED) {
			warnings.add("Invitation was already claimed.");
			return RosterRowAction.ALREADY_ENROLLED;
		}
		return RosterRowAction.READY_INVITE;
	}

	private ApplyResult applyRow(Course course, RosterPreviewRow row) {
		if (row.action() == RosterRowAction.ALREADY_ENROLLED || row.action() == RosterRowAction.ALREADY_INVITED) {
			return ApplyResult.noop();
		}
		if (row.action() == RosterRowAction.READY_ENROLL) {
			return enrollExisting(course, row);
		}
		if (row.action() == RosterRowAction.READY_INVITE) {
			return inviteNew(course, row);
		}
		return ApplyResult.noop();
	}

	private ApplyResult enrollExisting(Course course, RosterPreviewRow row) {
		UserAccount account = store.findUserByEmail(row.email()).orElse(null);
		if (account == null || account.getAccountRole() != AccountRole.STUDENT) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, HttpStatus.CONFLICT, "Student account changed after preview.");
		}
		StudentProfile profile = store.findStudentByUserId(account.getId()).orElseThrow(() -> new AcademicException(
				AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, HttpStatus.CONFLICT, "Student profile is missing."));
		CourseEnrollment enrollment = store.findEnrollment(profile.getId(), course.getId()).orElse(null);
		if (enrollment == null) {
			enrollment = new CourseEnrollment();
			enrollment.setStudentProfile(profile);
			enrollment.setCourse(course);
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			store.saveEnrollment(enrollment);
		} else if (enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			store.saveEnrollment(enrollment);
		} else {
			return ApplyResult.noop();
		}
		enqueue(
				account.getEmail(),
				account.getId(),
				"COURSE_ENROLLED",
				EmailTemplateService.COURSE_ENROLLED,
				mailPayload(
						EmailTemplateService.COURSE_ENROLLED,
						course,
						account.getFullName(),
						account.getEmail(),
						false));
		return new ApplyResult(1, 0, 0, 1);
	}

	private ApplyResult inviteNew(Course course, RosterPreviewRow row) {
		StudentCourseInvitation invitation = store.findInvitationByCourseAndEmail(course.getId(), row.email())
				.or(() -> store.findInvitationByCourseAndStudentCode(course.getId(), row.studentCode()))
				.orElse(null);
		if (invitation != null && (isOutstandingInvitation(invitation.getInvitationStatus())
				|| invitation.getInvitationStatus() == StudentInvitationStatus.CLAIMED)) {
			return ApplyResult.noop();
		}
		if (invitation == null) {
			invitation = new StudentCourseInvitation();
			invitation.setCourse(course);
			invitation.setInvitationType(StudentInvitationType.COURSE_JOIN);
			invitation.setAttemptCount(0);
			invitation.setVersion(0L);
		}
		invitation.setEmail(row.email());
		invitation.setStudentCode(row.studentCode());
		invitation.setFullName(row.fullName());
		invitation.setInvitationStatus(StudentInvitationStatus.PENDING);
		invitation.setStudentProfile(null);
		store.saveInvitation(invitation);
		enqueue(
				row.email(),
				null,
				"COURSE_INVITATION",
				EmailTemplateService.COURSE_INVITATION,
				mailPayload(
						EmailTemplateService.COURSE_INVITATION,
						course,
						row.fullName(),
						row.email(),
						institutionalEmails.isInstitutionalEmail(row.email())));
		return new ApplyResult(0, 1, 0, 1);
	}

	private void enqueue(String email, UUID userId, String type, String template, Map<String, Object> payload) {
		emails.enqueue(new EmailEnqueueRequest(email, userId, type, template, payload, null));
	}

	private Map<String, Object> mailPayload(
			String templateKey, Course course, String fullName, String recipientEmail, boolean institutional) {
		return templates.payload(
				templateKey,
				EmailTemplateModel.course(
						fullName,
						recipientEmail,
						course.getName(),
						course.getSubject().getSubjectCode(),
						course.getAcademicClass().getClassCode(),
						course.getSemester().getCode(),
						course.getSemester().getName(),
						institutional));
	}

	private <T> T writeAtomic(Supplier<T> action) {
		if (transactionManager != null) {
			return new TransactionTemplate(transactionManager).execute(status -> action.get());
		}
		return store.inTransaction(action);
	}

	private Course requireCourse(UUID courseId) {
		return store.findCourse(courseId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.COURSE_NOT_FOUND, HttpStatus.NOT_FOUND, "Course was not found."));
	}

	private static boolean isOutstandingInvitation(StudentInvitationStatus status) {
		return status != null && status.isOutstanding();
	}

	private static boolean hasBlockingErrors(List<RosterPreviewRow> rows) {
		return rows.stream()
				.anyMatch(row -> row.action() == RosterRowAction.INVALID || row.action() == RosterRowAction.CONFLICT);
	}

	private static RosterPreviewSummary summary(List<RosterPreviewRow> rows) {
		int invalid = 0;
		int enroll = 0;
		int invite = 0;
		int alreadyEnrolled = 0;
		int alreadyInvited = 0;
		for (RosterPreviewRow row : rows) {
			switch (row.action()) {
				case READY_ENROLL -> enroll++;
				case READY_INVITE -> invite++;
				case ALREADY_ENROLLED -> alreadyEnrolled++;
				case ALREADY_INVITED -> alreadyInvited++;
				case INVALID, CONFLICT -> invalid++;
			}
		}
		return new RosterPreviewSummary(
				rows.size(), rows.size() - invalid, invalid, enroll, invite, alreadyEnrolled, alreadyInvited);
	}

	private String newToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	static String normalizeEmail(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
	}

	static String normalizeCode(String value) {
		return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
	}

	static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	static String sanitize(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		String trimmed = value.trim();
		char first = trimmed.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n') {
			return "'" + trimmed;
		}
		return trimmed;
	}

	private record ApplyResult(int enrolledCount, int invitedCount, int unchangedCount, int emailCount) {
		static ApplyResult noop() {
			return new ApplyResult(0, 0, 1, 0);
		}
	}
}
