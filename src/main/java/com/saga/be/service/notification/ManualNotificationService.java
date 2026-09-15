package com.saga.be.service.notification;

import com.saga.be.dto.notification.ManualNotificationRequest;
import com.saga.be.dto.notification.NotificationSendResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.BroadcastAudience;
import com.saga.be.entity.enums.BroadcastStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.notification.NotificationBroadcast;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.push.FcmPayload;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.NotificationBroadcastRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class ManualNotificationService {

	static final String ACTION_SYSTEM = "SYSTEM_NOTIFICATION_SENT";
	static final String ACTION_LECTURER = "LECTURER_NOTIFICATION_SENT";
	static final String SCOPE_ALL = "ALL";
	static final String SCOPE_ALL_COURSES = "ALL_COURSES";
	static final String SCOPE_COURSE = "COURSE";
	static final String SCOPE_TEAM = "TEAM";
	static final String SCOPE_STUDENT = "STUDENT";

	private static final List<AccountRole> PRODUCT_ROLES = List.of(AccountRole.STUDENT, AccountRole.LECTURER);

	private final UserAccountRepository users;
	private final NotificationBroadcastRepository broadcasts;
	private final NotificationService notifications;
	private final NotificationDeliveryRepository deliveries;
	private final UserNotificationRepository userNotifications;
	private final LecturerCourseAuthorization authorization;
	private final LecturerProfileRepository lecturerProfiles;
	private final CourseRepository courses;
	private final CourseEnrollmentRepository enrollments;
	private final TeamRepository teams;
	private final TeamMemberRepository teamMembers;
	private final StudentProfileRepository students;
	private final AuditService auditLogs;

	public ManualNotificationService(
			UserAccountRepository users,
			NotificationBroadcastRepository broadcasts,
			NotificationService notifications,
			NotificationDeliveryRepository deliveries,
			UserNotificationRepository userNotifications,
			LecturerCourseAuthorization authorization,
			LecturerProfileRepository lecturerProfiles,
			CourseRepository courses,
			CourseEnrollmentRepository enrollments,
			TeamRepository teams,
			TeamMemberRepository teamMembers,
			StudentProfileRepository students,
			AuditService auditLogs) {
		this.users = users;
		this.broadcasts = broadcasts;
		this.notifications = notifications;
		this.deliveries = deliveries;
		this.userNotifications = userNotifications;
		this.authorization = authorization;
		this.lecturerProfiles = lecturerProfiles;
		this.courses = courses;
		this.enrollments = enrollments;
		this.teams = teams;
		this.teamMembers = teamMembers;
		this.students = students;
		this.auditLogs = auditLogs;
	}

	@Transactional
	public NotificationSendResponse sendSystem(
			UUID actorId, String idempotencyKey, ManualNotificationRequest request, AuditRequest audit) {
		return sendSystem(requireActor(actorId), idempotencyKey, request, audit);
	}

	@Transactional
	public NotificationSendResponse sendSystem(
			UserAccount actor, String idempotencyKey, ManualNotificationRequest request, AuditRequest audit) {
		requireRole(actor, AccountRole.ADMIN);
		List<UUID> recipients = users.findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(
				AccountStatus.ACTIVE, PRODUCT_ROLES);
		return send(
				actor,
				idempotencyKey,
				request,
				audit,
				new SendPlan(
						BroadcastAudience.ALL,
						SCOPE_ALL,
						NotificationType.SYSTEM,
						ACTION_SYSTEM,
						null,
						null,
						null,
						sortedWithoutSender(recipients, actor.getId()),
						null));
	}

	@Transactional
	public NotificationSendResponse sendAllCourses(
			UUID actorId, String idempotencyKey, ManualNotificationRequest request, AuditRequest audit) {
		return sendAllCourses(requireActor(actorId), idempotencyKey, request, audit);
	}

	@Transactional
	public NotificationSendResponse sendAllCourses(
			UserAccount actor, String idempotencyKey, ManualNotificationRequest request, AuditRequest audit) {
		requireRole(actor, AccountRole.LECTURER);
		List<UUID> courseIds = ownedCourseIds(actor);
		List<UUID> recipients = courseIds.isEmpty()
				? List.of()
				: eligibleStudentIds(enrollments.findFetchedByCourse_IdInAndEnrollmentStatus(
						courseIds, EnrollmentStatus.ACTIVE));
		return send(
				actor,
				idempotencyKey,
				request,
				audit,
				new SendPlan(
						BroadcastAudience.ALL_COURSES,
						SCOPE_ALL_COURSES,
						NotificationType.COURSE,
						ACTION_LECTURER,
						null,
						null,
						null,
						sortedWithoutSender(recipients, actor.getId()),
						null));
	}

	@Transactional
	public NotificationSendResponse sendCourse(
			UUID actorId,
			UUID courseId,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit) {
		return sendCourse(requireActor(actorId), courseId, idempotencyKey, request, audit);
	}

	@Transactional
	public NotificationSendResponse sendCourse(
			UserAccount actor,
			UUID courseId,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit) {
		requireRole(actor, AccountRole.LECTURER);
		Course course = authorization.requireCourse(actor, courseId);
		List<UUID> recipients = eligibleStudentIds(
				enrollments.findFetchedByCourse_IdAndEnrollmentStatus(course.getId(), EnrollmentStatus.ACTIVE));
		return send(
				actor,
				idempotencyKey,
				request,
				audit,
				new SendPlan(
						BroadcastAudience.COURSE,
						SCOPE_COURSE,
						NotificationType.COURSE,
						ACTION_LECTURER,
						course.getId(),
						null,
						null,
						sortedWithoutSender(recipients, actor.getId()),
						null));
	}

	@Transactional
	public NotificationSendResponse sendTeam(
			UUID actorId,
			UUID teamId,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit) {
		return sendTeam(requireActor(actorId), teamId, idempotencyKey, request, audit);
	}

	@Transactional
	public NotificationSendResponse sendTeam(
			UserAccount actor,
			UUID teamId,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit) {
		requireRole(actor, AccountRole.LECTURER);
		Team team = requireSupervisedTeam(actor, teamId);
		List<UUID> recipients = eligibleTeamMemberIds(team.getId());
		return send(
				actor,
				idempotencyKey,
				request,
				audit,
				new SendPlan(
						BroadcastAudience.TEAM,
						SCOPE_TEAM,
						NotificationType.TEAM,
						ACTION_LECTURER,
						team.getCourse().getId(),
						team.getId(),
						null,
						sortedWithoutSender(recipients, actor.getId()),
						team));
	}

	@Transactional
	public NotificationSendResponse sendStudent(
			UUID actorId,
			UUID courseId,
			UUID studentId,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit) {
		return sendStudent(requireActor(actorId), courseId, studentId, idempotencyKey, request, audit);
	}

	@Transactional
	public NotificationSendResponse sendStudent(
			UserAccount actor,
			UUID courseId,
			UUID studentId,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit) {
		requireRole(actor, AccountRole.LECTURER);
		Course course = authorization.requireCourse(actor, courseId);
		UUID recipientId = requireEligibleCourseStudent(course.getId(), studentId);
		return send(
				actor,
				idempotencyKey,
				request,
				audit,
				new SendPlan(
						BroadcastAudience.STUDENT,
						SCOPE_STUDENT,
						NotificationType.COURSE,
						ACTION_LECTURER,
						course.getId(),
						null,
						studentId,
						sortedWithoutSender(List.of(recipientId), actor.getId()),
						null));
	}

	private NotificationSendResponse send(
			UserAccount actor,
			String idempotencyKey,
			ManualNotificationRequest request,
			AuditRequest audit,
			SendPlan plan) {
		String key = requireIdempotencyKey(idempotencyKey);
		String title = requireText(request == null ? null : request.title(), 160, "title");
		String message = requireText(request == null ? null : request.message(), 1000, "message");
		String actionUrl = safeActionUrl(request == null ? null : request.actionUrl());
		String fingerprint = fingerprint(plan, title, message, actionUrl);
		UserAccount sender = lockSenderAndRecipients(actor.getId(), plan.recipientIds());
		NotificationBroadcast existing =
				broadcasts.findBySenderUser_IdAndIdempotencyKeyForUpdate(sender.getId(), key).orElse(null);
		if (existing != null) {
			if (!fingerprint.equals(existing.getRequestFingerprint())) {
				throw new AcademicException(
						AcademicErrorCode.NOTIFICATION_SEND_CONFLICT,
						HttpStatus.CONFLICT,
						"Idempotency key was already used with a different notification payload.");
			}
			return new NotificationSendResponse(
					existing.getId(), plan.scope(), existing.getRecipientCount(), 0);
		}
		NotificationBroadcast broadcast = new NotificationBroadcast();
		broadcast.setSenderUser(sender);
		broadcast.setAudience(plan.audience());
		broadcast.setTitle(title);
		broadcast.setMessage(message);
		broadcast.setIdempotencyKey(key);
		broadcast.setRequestFingerprint(fingerprint);
		broadcast.setStatus(BroadcastStatus.QUEUED);
		broadcast.setRecipientCount(plan.recipientIds().size());
		broadcast.setNotificationCount(0);
		broadcast.setDeliveryQueuedCount(0);
		broadcasts.saveAndFlush(broadcast);
		int queued = 0;
		for (UUID recipientId : plan.recipientIds()) {
			var created = notifications.createNotification(
					recipientId,
					plan.type(),
					title,
					message,
					actionUrl,
					eventKey(broadcast.getId(), recipientId),
					broadcast);
			queued += (int) deliveries.countByNotification_Id(created.id());
		}
		int createdCount = (int) userNotifications.countByBroadcast_Id(broadcast.getId());
		broadcast.setNotificationCount(createdCount);
		broadcast.setDeliveryQueuedCount(queued);
		broadcast.setStatus(BroadcastStatus.SENT);
		broadcast.setCompletedAt(LocalDateTime.now());
		broadcasts.save(broadcast);
		auditLogs.record(
				sender,
				null,
				plan.team(),
				plan.auditAction(),
				"NOTIFICATION_BROADCAST",
				broadcast.getId(),
				null,
				null,
				auditMetadata(plan, createdCount),
				AuditSource.API,
				audit == null ? null : audit.requestId(),
				audit == null ? null : audit.ip(),
				audit == null ? null : audit.userAgent());
		return new NotificationSendResponse(broadcast.getId(), plan.scope(), plan.recipientIds().size(), createdCount);
	}

	private UserAccount requireActor(UUID actorId) {
		if (actorId == null) {
			throw new AcademicException(
					AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found.");
		}
		return users.findById(actorId).orElseThrow(() -> new AcademicException(
				AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
	}

	/**
	 * Lock every {@code user_account} this send will touch in one global UUID order so ADMIN
	 * fan-out (lecturers as recipients) cannot deadlock with a lecturer send (lecturer locked as
	 * sender first). {@link NotificationService} may re-lock a recipient already held here.
	 */
	private UserAccount lockSenderAndRecipients(UUID senderId, List<UUID> recipientIds) {
		List<UUID> ordered = new ArrayList<>();
		ordered.add(senderId);
		if (recipientIds != null) {
			ordered.addAll(recipientIds);
		}
		UserAccount sender = null;
		for (UUID id : ordered.stream().filter(id -> id != null).distinct().sorted().toList()) {
			UserAccount locked = users.findByIdForUpdate(id).orElseThrow(() -> new AcademicException(
					AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
			if (id.equals(senderId)) {
				sender = locked;
			}
		}
		if (sender == null) {
			throw new AcademicException(
					AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found.");
		}
		return sender;
	}

	private List<UUID> ownedCourseIds(UserAccount actor) {
		LecturerProfile profile = lecturerProfiles.findByUserAccount_Id(actor.getId()).orElse(null);
		if (profile == null) {
			return List.of();
		}
		return courses.search(null, null, null, profile.getId()).stream().map(Course::getId).toList();
	}

	private List<UUID> eligibleStudentIds(List<CourseEnrollment> rows) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (CourseEnrollment enrollment : rows) {
			UserAccount account = enrollment.getStudentProfile() == null
					? null
					: enrollment.getStudentProfile().getUserAccount();
			if (account != null
					&& account.getId() != null
					&& account.getAccountStatus() == AccountStatus.ACTIVE) {
				ids.add(account.getId());
			}
		}
		return new ArrayList<>(ids);
	}

	private List<UUID> eligibleTeamMemberIds(UUID teamId) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (TeamMember member : teamMembers.findFetchedByTeam_Id(teamId)) {
			CourseEnrollment enrollment = member.getCourseEnrollment();
			if (enrollment == null || enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
				continue;
			}
			UserAccount account = enrollment.getStudentProfile() == null
					? null
					: enrollment.getStudentProfile().getUserAccount();
			if (account != null
					&& account.getId() != null
					&& account.getAccountStatus() == AccountStatus.ACTIVE) {
				ids.add(account.getId());
			}
		}
		return new ArrayList<>(ids);
	}

	private UUID requireEligibleCourseStudent(UUID courseId, UUID studentId) {
		if (studentId == null) {
			throw studentNotFound();
		}
		StudentProfile profile = students.findById(studentId).orElseGet(() -> students.findByUserAccount_Id(studentId).orElse(null));
		if (profile == null) {
			throw studentNotFound();
		}
		CourseEnrollment enrollment =
				enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), courseId).orElse(null);
		if (enrollment == null || enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			throw studentNotFound();
		}
		UserAccount account = profile.getUserAccount();
		if (account == null
				|| account.getId() == null
				|| account.getAccountStatus() != AccountStatus.ACTIVE) {
			throw studentNotFound();
		}
		return account.getId();
	}

	private Team requireSupervisedTeam(UserAccount actor, UUID teamId) {
		Team team = teams.findFetchedById(teamId).orElseThrow(ManualNotificationService::teamNotFound);
		if (team.getCourse() == null || team.getCourse().getId() == null) {
			throw teamNotFound();
		}
		try {
			authorization.requireCourse(actor, team.getCourse().getId());
		} catch (AcademicException ex) {
			if (ex.getCode() == AcademicErrorCode.COURSE_NOT_FOUND
					|| ex.getCode() == AcademicErrorCode.LECTURER_COURSE_FORBIDDEN) {
				throw teamNotFound();
			}
			throw ex;
		}
		return team;
	}

	private static List<UUID> sortedWithoutSender(List<UUID> ids, UUID senderId) {
		return ids.stream()
				.filter(id -> id != null && !id.equals(senderId))
				.distinct()
				.sorted(Comparator.naturalOrder())
				.toList();
	}

	private static String eventKey(UUID broadcastId, UUID recipientId) {
		return "manual:" + broadcastId + ":" + recipientId;
	}

	static String fingerprint(SendPlan plan, String title, String message, String actionUrl) {
		String canonical = plan.audience().name()
				+ '\n'
				+ nullToEmpty(plan.courseId())
				+ '\n'
				+ nullToEmpty(plan.teamId())
				+ '\n'
				+ nullToEmpty(plan.studentId())
				+ '\n'
				+ plan.type().name()
				+ '\n'
				+ title
				+ '\n'
				+ message
				+ '\n'
				+ (actionUrl == null ? "" : actionUrl);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required for notification send idempotency.", ex);
		}
	}

	private static Map<String, Object> auditMetadata(SendPlan plan, int createdCount) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("scope", plan.scope());
		metadata.put("notificationType", plan.type().name());
		metadata.put("recipientCount", plan.recipientIds().size());
		metadata.put("createdCount", createdCount);
		if (plan.courseId() != null) {
			metadata.put("courseId", plan.courseId().toString());
		}
		if (plan.teamId() != null) {
			metadata.put("teamId", plan.teamId().toString());
		}
		if (plan.studentId() != null) {
			metadata.put("studentId", plan.studentId().toString());
		}
		return metadata;
	}

	private static void requireRole(UserAccount actor, AccountRole required) {
		if (actor == null || actor.getAccountRole() != required) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID, HttpStatus.FORBIDDEN, "This notification send is not allowed.");
		}
	}

	private static String requireIdempotencyKey(String value) {
		return requireText(value, 128, "Idempotency-Key");
	}

	private static String safeActionUrl(String actionUrl) {
		if (!StringUtils.hasText(actionUrl)) {
			return null;
		}
		String trimmed = actionUrl.trim();
		if (trimmed.length() > 500) {
			throw invalid("actionUrl is too long.");
		}
		String safe = FcmPayload.navigationTarget(trimmed);
		if (safe == null) {
			throw invalid("actionUrl is not allowed.");
		}
		return safe;
	}

	private static String requireText(String value, int maxLength, String field) {
		if (!StringUtils.hasText(value)) {
			throw invalid(field + " is required.");
		}
		String trimmed = value.trim();
		if (trimmed.length() > maxLength) {
			throw invalid(field + " is too long.");
		}
		return trimmed;
	}

	private static String nullToEmpty(UUID value) {
		return value == null ? "" : value.toString();
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private static AcademicException teamNotFound() {
		return new AcademicException(AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Team was not found.");
	}

	private static AcademicException studentNotFound() {
		return new AcademicException(
				AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND, HttpStatus.NOT_FOUND, "Student was not found in this course.");
	}

	record SendPlan(
			BroadcastAudience audience,
			String scope,
			NotificationType type,
			String auditAction,
			UUID courseId,
			UUID teamId,
			UUID studentId,
			List<UUID> recipientIds,
			Team team) {}
}
