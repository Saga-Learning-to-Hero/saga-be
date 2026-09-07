package com.saga.be.service.roster;

import com.saga.be.auth.InstitutionalEmailPolicy;
import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.roster.AddRosterStudentRequest;
import com.saga.be.dto.roster.AddRosterStudentResponse;
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
import java.util.Collection;
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

	public AddRosterStudentResponse addStudent(
			UUID courseId, AddRosterStudentRequest request, UserAccount admin, AuditRequest auditRequest) {
		if (request == null) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, HttpStatus.BAD_REQUEST, "Student details are required.");
		}
		return writeAtomic(() -> applyAddStudent(requireCourse(courseId), request, admin, auditRequest));
	}

	private RosterConfirmResponse applyConfirm(
			Course course, List<RosterPreviewRow> rows, UserAccount admin, AuditRequest auditRequest) {
		int enrolled = 0;
		int invited = 0;
		int unchanged = 0;
		int emailsEnqueued = 0;
		RosterLookups lookups = RosterLookups.preload(
				store,
				course.getId(),
				rows.stream().map(RosterPreviewRow::email).toList(),
				rows.stream().map(RosterPreviewRow::studentCode).toList());
		for (RosterPreviewRow row : rows) {
			ApplyResult result = applyRow(course, row, lookups);
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

	private AddRosterStudentResponse applyAddStudent(
			Course course, AddRosterStudentRequest request, UserAccount admin, AuditRequest auditRequest) {
		CourseRosterWorkbook.RawRow raw = new CourseRosterWorkbook.RawRow(
				1,
				"1",
				course.getAcademicClass().getClassCode(),
				request.fullName(),
				request.studentCode(),
				request.email(),
				request.memberCode());
		RosterLookups lookups = RosterLookups.preload(
				store, course.getId(), List.of(normalizeEmail(raw.email())), List.of(normalizeCode(raw.studentCode())));
		RosterPreviewRow row = classifyWithLookups(course, List.of(raw), lookups).getFirst();
		if (row.action() == RosterRowAction.INVALID || row.action() == RosterRowAction.CONFLICT) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED,
					HttpStatus.CONFLICT,
					row.errors().isEmpty() ? "Roster student could not be added." : row.errors().getFirst());
		}
		ApplyResult applied = applyRow(course, row, lookups);
		audit.record(
				admin,
				null,
				null,
				COURSE_ROSTER_IMPORTED,
				"course",
				course.getId(),
				null,
				Map.of("classCode", course.getAcademicClass().getClassCode()),
				Map.of(
						"enrolled",
						applied.enrolledCount(),
						"invited",
						applied.invitedCount(),
						"unchanged",
						applied.unchangedCount(),
						"emailsEnqueued",
						applied.emailCount()),
				AuditSource.API,
				auditRequest == null ? null : auditRequest.requestId(),
				auditRequest == null ? null : auditRequest.ip(),
				auditRequest == null ? null : auditRequest.userAgent());
		return new AddRosterStudentResponse(resultOf(row, applied), entryOf(row, lookups));
	}

	@Transactional(readOnly = true)
	public CourseRosterResponse getRoster(UUID courseId) {
		Course course = requireCourse(courseId);
		List<CourseRosterEntryResponse> entries = new ArrayList<>();
		for (CourseEnrollment enrollment : store.listEnrollments(courseId)) {
			entries.add(enrollmentEntry(enrollment));
		}
		for (StudentCourseInvitation invitation : store.listInvitations(courseId)) {
			if (invitation.getInvitationStatus() == null || !invitation.getInvitationStatus().isOutstanding()) {
				continue;
			}
			entries.add(invitationEntry(invitation));
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
		List<String> lookupEmails = new ArrayList<>();
		List<String> lookupCodes = new ArrayList<>();
		for (CourseRosterWorkbook.RawRow raw : rawRows) {
			String email = normalizeEmail(raw.email());
			String studentCode = normalizeCode(raw.studentCode());
			if (StringUtils.hasText(email)) {
				lookupEmails.add(email);
			}
			if (StringUtils.hasText(studentCode)) {
				lookupCodes.add(studentCode);
			}
		}
		RosterLookups lookups = RosterLookups.preload(store, course.getId(), lookupEmails, lookupCodes);
		return classifyWithLookups(course, rawRows, lookups);
	}

	private List<RosterPreviewRow> classifyWithLookups(
			Course course, List<CourseRosterWorkbook.RawRow> rawRows, RosterLookups lookups) {
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
				action = classifyIdentity(lookups, email, studentCode, errors, warnings);
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
			RosterLookups lookups, String email, String studentCode, List<String> errors, List<String> warnings) {
		UserAccount account = lookups.userByEmail(email);
		StudentProfile byCode = lookups.studentByCode(studentCode);
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
			StudentProfile profile = lookups.studentByUserId(account.getId());
			if (profile == null) {
				errors.add("Student profile is missing.");
				return RosterRowAction.CONFLICT;
			}
			if (StringUtils.hasText(profile.getStudentCode())
					&& !profile.getStudentCode().equalsIgnoreCase(studentCode)) {
				errors.add("StudentCode does not match the existing Student profile.");
				return RosterRowAction.CONFLICT;
			}
			CourseEnrollment enrollment = lookups.enrollmentByProfileId(profile.getId());
			if (enrollment != null && enrollment.getEnrollmentStatus() == EnrollmentStatus.ACTIVE) {
				return RosterRowAction.ALREADY_ENROLLED;
			}
			return RosterRowAction.READY_ENROLL;
		}
		StudentCourseInvitation byEmail = lookups.invitationByEmail(email);
		StudentCourseInvitation byStudentCode = lookups.invitationByStudentCode(studentCode);
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

	private ApplyResult applyRow(Course course, RosterPreviewRow row, RosterLookups lookups) {
		if (row.action() == RosterRowAction.ALREADY_ENROLLED || row.action() == RosterRowAction.ALREADY_INVITED) {
			return ApplyResult.noop();
		}
		if (row.action() == RosterRowAction.READY_ENROLL) {
			return enrollExisting(course, row, lookups);
		}
		if (row.action() == RosterRowAction.READY_INVITE) {
			return inviteNew(course, row, lookups);
		}
		return ApplyResult.noop();
	}

	private ApplyResult enrollExisting(Course course, RosterPreviewRow row, RosterLookups lookups) {
		UserAccount account = lookups.userByEmail(row.email());
		if (account == null || account.getAccountRole() != AccountRole.STUDENT) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, HttpStatus.CONFLICT, "Student account changed after preview.");
		}
		StudentProfile profile = lookups.studentByUserId(account.getId());
		if (profile == null) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, HttpStatus.CONFLICT, "Student profile is missing.");
		}
		CourseEnrollment enrollment = lookups.enrollmentByProfileId(profile.getId());
		if (enrollment == null) {
			enrollment = new CourseEnrollment();
			enrollment.setStudentProfile(profile);
			enrollment.setCourse(course);
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			store.saveEnrollment(enrollment);
			lookups.rememberEnrollment(enrollment);
		} else if (enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			store.saveEnrollment(enrollment);
			lookups.rememberEnrollment(enrollment);
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

	private ApplyResult inviteNew(Course course, RosterPreviewRow row, RosterLookups lookups) {
		StudentCourseInvitation invitation = lookups.invitationByEmail(row.email());
		if (invitation == null) {
			invitation = lookups.invitationByStudentCode(row.studentCode());
		}
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
		lookups.rememberInvitation(invitation);
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

	private static String resultOf(RosterPreviewRow row, ApplyResult applied) {
		return switch (row.action()) {
			case ALREADY_ENROLLED -> "ALREADY_ENROLLED";
			case ALREADY_INVITED -> "ALREADY_INVITED";
			case READY_ENROLL -> applied.enrolledCount() > 0 ? "ENROLLED" : "ALREADY_ENROLLED";
			case READY_INVITE -> applied.invitedCount() > 0 ? "INVITED" : "ALREADY_INVITED";
			case INVALID, CONFLICT -> throw new AcademicException(
					AcademicErrorCode.ROSTER_CONFIRM_BLOCKED,
					HttpStatus.CONFLICT,
					"Roster student could not be added.");
		};
	}

	private CourseRosterEntryResponse entryOf(RosterPreviewRow row, RosterLookups lookups) {
		UserAccount account = lookups.userByEmail(row.email());
		if (account != null) {
			StudentProfile profile = lookups.studentByUserId(account.getId());
			CourseEnrollment enrollment = profile == null ? null : lookups.enrollmentByProfileId(profile.getId());
			if (enrollment != null) {
				return enrollmentEntry(enrollment);
			}
		}
		StudentCourseInvitation invitation = lookups.invitationByEmail(row.email());
		if (invitation == null) {
			invitation = lookups.invitationByStudentCode(row.studentCode());
		}
		if (invitation != null && isOutstandingInvitation(invitation.getInvitationStatus())) {
			return invitationEntry(invitation);
		}
		return null;
	}

	private static CourseRosterEntryResponse enrollmentEntry(CourseEnrollment enrollment) {
		StudentProfile profile = enrollment.getStudentProfile();
		UserAccount user = profile == null ? null : profile.getUserAccount();
		return new CourseRosterEntryResponse(
				"ENROLLMENT",
				enrollment.getId(),
				null,
				user == null ? null : user.getId(),
				sanitize(profile == null ? null : profile.getStudentCode()),
				sanitize(user == null ? null : user.getFullName()),
				sanitize(user == null ? null : user.getEmail()),
				enrollment.getEnrollmentStatus() == null ? null : enrollment.getEnrollmentStatus().name(),
				null,
				"REGISTERED");
	}

	private static CourseRosterEntryResponse invitationEntry(StudentCourseInvitation invitation) {
		return new CourseRosterEntryResponse(
				"INVITATION",
				null,
				invitation.getId(),
				null,
				sanitize(invitation.getStudentCode()),
				sanitize(invitation.getFullName()),
				sanitize(invitation.getEmail()),
				null,
				invitation.getInvitationStatus() == null ? null : invitation.getInvitationStatus().name(),
				"NOT_REGISTERED");
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

	static final class RosterLookups {
		private final Map<String, UserAccount> usersByEmail = new LinkedHashMap<>();
		private final Map<String, StudentProfile> studentsByCode = new LinkedHashMap<>();
		private final Map<UUID, StudentProfile> studentsByUserId = new LinkedHashMap<>();
		private final Map<UUID, CourseEnrollment> enrollmentsByProfileId = new LinkedHashMap<>();
		private final Map<String, StudentCourseInvitation> invitationsByEmail = new LinkedHashMap<>();
		private final Map<String, StudentCourseInvitation> invitationsByCode = new LinkedHashMap<>();

		static RosterLookups preload(
				CourseRosterStore store, UUID courseId, Collection<String> emails, Collection<String> studentCodes) {
			RosterLookups lookups = new RosterLookups();
			List<String> emailKeys = distinctNormalizedEmails(emails);
			List<String> codeKeys = distinctNormalizedCodes(studentCodes);
			for (UserAccount user : store.findUsersByEmails(emailKeys)) {
				if (user != null && StringUtils.hasText(user.getEmail())) {
					lookups.usersByEmail.putIfAbsent(normalizeEmail(user.getEmail()), user);
				}
			}
			for (StudentProfile profile : store.findStudentsByCodes(codeKeys)) {
				lookups.rememberStudent(profile);
			}
			List<UUID> userIds = lookups.usersByEmail.values().stream().map(UserAccount::getId).distinct().toList();
			for (StudentProfile profile : store.findStudentsByUserIds(userIds)) {
				lookups.rememberStudent(profile);
			}
			for (CourseEnrollment enrollment : store.listEnrollments(courseId)) {
				lookups.rememberEnrollment(enrollment);
			}
			for (StudentCourseInvitation invitation : store.listInvitations(courseId)) {
				lookups.rememberInvitation(invitation);
			}
			return lookups;
		}

		UserAccount userByEmail(String email) {
			return StringUtils.hasText(email) ? usersByEmail.get(normalizeEmail(email)) : null;
		}

		StudentProfile studentByCode(String studentCode) {
			return StringUtils.hasText(studentCode) ? studentsByCode.get(normalizeCode(studentCode)) : null;
		}

		StudentProfile studentByUserId(UUID userId) {
			return userId == null ? null : studentsByUserId.get(userId);
		}

		CourseEnrollment enrollmentByProfileId(UUID studentProfileId) {
			return studentProfileId == null ? null : enrollmentsByProfileId.get(studentProfileId);
		}

		StudentCourseInvitation invitationByEmail(String email) {
			return StringUtils.hasText(email) ? invitationsByEmail.get(normalizeEmail(email)) : null;
		}

		StudentCourseInvitation invitationByStudentCode(String studentCode) {
			return StringUtils.hasText(studentCode) ? invitationsByCode.get(normalizeCode(studentCode)) : null;
		}

		void rememberEnrollment(CourseEnrollment enrollment) {
			if (enrollment != null && enrollment.getStudentProfile() != null) {
				enrollmentsByProfileId.put(enrollment.getStudentProfile().getId(), enrollment);
			}
		}

		void rememberInvitation(StudentCourseInvitation invitation) {
			if (invitation == null) {
				return;
			}
			if (StringUtils.hasText(invitation.getEmail())) {
				invitationsByEmail.put(normalizeEmail(invitation.getEmail()), invitation);
			}
			if (StringUtils.hasText(invitation.getStudentCode())) {
				invitationsByCode.put(normalizeCode(invitation.getStudentCode()), invitation);
			}
		}

		private void rememberStudent(StudentProfile profile) {
			if (profile == null) {
				return;
			}
			if (StringUtils.hasText(profile.getStudentCode())) {
				studentsByCode.putIfAbsent(normalizeCode(profile.getStudentCode()), profile);
			}
			if (profile.getUserAccount() != null) {
				studentsByUserId.putIfAbsent(profile.getUserAccount().getId(), profile);
			}
		}

		private static List<String> distinctNormalizedEmails(Collection<String> emails) {
			if (emails == null || emails.isEmpty()) {
				return List.of();
			}
			return emails.stream().map(CourseRosterService::normalizeEmail).filter(StringUtils::hasText).distinct().toList();
		}

		private static List<String> distinctNormalizedCodes(Collection<String> studentCodes) {
			if (studentCodes == null || studentCodes.isEmpty()) {
				return List.of();
			}
			return studentCodes.stream()
					.map(CourseRosterService::normalizeCode)
					.filter(StringUtils::hasText)
					.distinct()
					.toList();
		}
	}
}
