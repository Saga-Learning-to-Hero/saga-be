package com.saga.be.service.team;

import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.team.LecturerCourseTeamsResponse;
import com.saga.be.dto.team.LecturerTeamMemberResponse;
import com.saga.be.dto.team.LecturerTeamResponse;
import com.saga.be.dto.team.TeamConfirmResponse;
import com.saga.be.dto.team.TeamPreviewResponse;
import com.saga.be.dto.team.TeamPreviewRow;
import com.saga.be.dto.team.TeamPreviewSummary;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TeamRowAction;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.mail.template.EmailTemplateModel;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.mail.EmailOutboxService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
public class LecturerTeamService {

	public static final String TEAM_ASSIGNMENT_IMPORTED = "TEAM_ASSIGNMENT_IMPORTED";
	public static final String TEAM_CREATED = "TEAM_CREATED";
	public static final String TEAM_RENAMED = "TEAM_RENAMED";
	public static final String TEAM_MEMBER_ASSIGNED = "TEAM_MEMBER_ASSIGNED";
	public static final String TEAM_MEMBER_REASSIGNED = "TEAM_MEMBER_REASSIGNED";
	public static final String TEAM_MEMBER_ROLE_CHANGED = "TEAM_MEMBER_ROLE_CHANGED";

	private final LecturerCourseAuthorization authorization;
	private final LecturerTeamStore store;
	private final TeamPreviewStore previews;
	private final RosterProperties rosterProperties;
	private final EmailOutboxService emails;
	private final EmailTemplateService templates;
	private final AuditService audit;
	private final PlatformTransactionManager transactionManager;
	private final SecureRandom random = new SecureRandom();

	public LecturerTeamService(
			LecturerCourseAuthorization authorization,
			LecturerTeamStore store,
			TeamPreviewStore previews,
			RosterProperties rosterProperties,
			AuthProperties authProperties,
			EmailOutboxService emails,
			AuditService audit) {
		this(authorization, store, previews, rosterProperties, emails, new EmailTemplateService(authProperties), audit, null);
	}

	@Autowired
	public LecturerTeamService(
			LecturerCourseAuthorization authorization,
			LecturerTeamStore store,
			TeamPreviewStore previews,
			RosterProperties rosterProperties,
			AuthProperties authProperties,
			EmailOutboxService emails,
			AuditService audit,
			EmailTemplateService templates,
			PlatformTransactionManager transactionManager) {
		this(
				authorization,
				store,
				previews,
				rosterProperties,
				emails,
				templates == null ? new EmailTemplateService(authProperties) : templates,
				audit,
				transactionManager);
	}

	LecturerTeamService(
			LecturerCourseAuthorization authorization,
			LecturerTeamStore store,
			TeamPreviewStore previews,
			RosterProperties rosterProperties,
			EmailOutboxService emails,
			EmailTemplateService templates,
			AuditService audit,
			PlatformTransactionManager transactionManager) {
		this.authorization = authorization;
		this.store = store;
		this.previews = previews;
		this.rosterProperties = rosterProperties;
		this.emails = emails;
		this.templates = templates;
		this.audit = audit;
		this.transactionManager = transactionManager;
	}

	@Transactional(readOnly = true)
	public byte[] template(UserAccount actor, UUID courseId) {
		Course course = authorization.requireCourse(actor, courseId);
		String classCode = classCode(course);
		Map<UUID, TeamMember> memberships = membershipsByEnrollment(courseId);
		List<LecturerTeamWorkbook.TemplateRow> rows = new ArrayList<>();
		for (CourseEnrollment enrollment : store.listActiveEnrollments(courseId)) {
			StudentProfile profile = enrollment.getStudentProfile();
			UserAccount user = profile == null ? null : profile.getUserAccount();
			TeamMember member = memberships.get(enrollment.getId());
			Team team = member == null ? null : member.getTeam();
			rows.add(new LecturerTeamWorkbook.TemplateRow(
					classCode,
					user == null ? "" : text(user.getFullName()),
					profile == null ? "" : text(profile.getStudentCode()),
					user == null ? "" : text(user.getEmail()),
					team == null ? null : team.getTeamNo(),
					team == null ? "" : text(team.getName()),
					displayRole(member == null ? null : member.getRoleInTeam())));
		}
		return LecturerTeamWorkbook.template(classCode, rows);
	}

	@Transactional(readOnly = true)
	public TeamPreviewResponse preview(UserAccount actor, UUID courseId, byte[] file) {
		Course course = authorization.requireCourse(actor, courseId);
		if (file == null || file.length == 0) {
			throw new AcademicException(AcademicErrorCode.TEAM_FILE_INVALID, HttpStatus.BAD_REQUEST, "Team file is required.");
		}
		if (file.length > rosterProperties.getMaxFileBytes()) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_FILE_TOO_LARGE, HttpStatus.BAD_REQUEST, "Team file is too large.");
		}
		List<LecturerTeamWorkbook.RawRow> rawRows = LecturerTeamWorkbook.parse(file);
		Classified classified = classify(course, rawRows);
		String token = newToken();
		previews.save(
				token,
				new TeamPreviewSnapshot(
						actor.getId(), course.getId(), classCode(course), classified.blockingErrors(), classified.rows()),
				rosterProperties.getPreviewTtl());
		return toPreviewResponse(token, course, classified);
	}

	public TeamConfirmResponse confirm(UserAccount actor, UUID courseId, String previewToken, AuditRequest auditRequest) {
		if (!StringUtils.hasText(previewToken)) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_PREVIEW_INVALID, HttpStatus.BAD_REQUEST, "Preview token is required.");
		}
		Course course = authorization.requireCourse(actor, courseId);
		String token = previewToken.trim();
		TeamPreviewSnapshot snapshot = previews
				.find(token)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_PREVIEW_EXPIRED, HttpStatus.BAD_REQUEST, "Team preview expired or was not found."));
		if (!snapshot.actorUserId().equals(actor.getId()) || !snapshot.courseId().equals(course.getId())) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_PREVIEW_MISMATCH,
					HttpStatus.FORBIDDEN,
					"Team preview does not match this lecturer or course.");
		}
		if (hasBlockingErrors(snapshot.blockingErrors(), snapshot.rows())) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_CONFIRM_BLOCKED,
					HttpStatus.CONFLICT,
					"Team preview contains blocking errors.");
		}
		TeamConfirmResponse result =
				writeAtomic(() -> applyConfirm(authorization.requireCourse(actor, courseId), snapshot.rows(), actor, auditRequest));
		previews.delete(token);
		return result;
	}

	@Transactional(readOnly = true)
	public LecturerCourseTeamsResponse listTeams(UserAccount actor, UUID courseId) {
		Course course = authorization.requireCourse(actor, courseId);
		Map<UUID, List<TeamMember>> membersByTeam = new LinkedHashMap<>();
		for (TeamMember member : store.listMembers(courseId)) {
			if (member.getTeam() == null) {
				continue;
			}
			membersByTeam.computeIfAbsent(member.getTeam().getId(), key -> new ArrayList<>()).add(member);
		}
		List<LecturerTeamResponse> teams = store.listTeams(courseId).stream()
				.map(team -> new LecturerTeamResponse(
						team.getId(),
						team.getTeamNo() == null ? 0 : team.getTeamNo(),
						team.getName(),
						team.getProject() == null ? null : team.getProject().getId(),
						sortMembers(membersByTeam.getOrDefault(team.getId(), List.of())).stream()
								.map(this::toLecturerMember)
								.toList()))
				.toList();
		return new LecturerCourseTeamsResponse(course.getId(), teams);
	}

	private Classified classify(Course course, List<LecturerTeamWorkbook.RawRow> rawRows) {
		String expectedClass = normalizeCode(classCode(course));
		List<CourseEnrollment> active = store.listActiveEnrollments(course.getId());
		Map<String, CourseEnrollment> byCode = new LinkedHashMap<>();
		for (CourseEnrollment enrollment : active) {
			StudentProfile profile = enrollment.getStudentProfile();
			if (profile != null && StringUtils.hasText(profile.getStudentCode())) {
				byCode.put(normalizeCode(profile.getStudentCode()), enrollment);
			}
		}
		Map<UUID, TeamMember> memberships = membershipsByEnrollment(course.getId());
		Map<Integer, Team> existingTeams = new LinkedHashMap<>();
		for (Team team : store.listTeams(course.getId())) {
			if (team.getTeamNo() != null) {
				existingTeams.put(team.getTeamNo(), team);
			}
		}
		Map<String, Integer> seenCodes = new LinkedHashMap<>();
		List<TeamPreviewRow> rows = new ArrayList<>();
		Set<UUID> seenEnrollments = new LinkedHashSet<>();
		for (LecturerTeamWorkbook.RawRow raw : rawRows) {
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();
			Integer teamNo = parseTeamNo(raw.teamNo(), errors);
			String teamName = text(raw.teamName());
			RoleInTeam role = parseRole(raw.teamRole(), errors);
			CourseEnrollment enrollment = null;
			String studentCode = text(raw.studentCode());
			if (!StringUtils.hasText(studentCode)) {
				errors.add("StudentCode is required.");
			} else {
				Integer firstRow = seenCodes.putIfAbsent(normalizeCode(studentCode), raw.rowNumber());
				if (firstRow != null) {
					errors.add("Duplicate student row.");
				}
				enrollment = byCode.get(normalizeCode(studentCode));
				if (enrollment == null) {
					errors.add("Student is not an ACTIVE enrollment in this course.");
				} else {
					seenEnrollments.add(enrollment.getId());
					identityMismatch(enrollment, expectedClass, raw, errors);
				}
			}
			if (!StringUtils.hasText(teamName)) {
				errors.add("TeamName is required.");
			}
			TeamRowAction action = errors.isEmpty()
					? desiredAction(enrollment, teamNo, role, memberships, existingTeams)
					: actionForErrors(errors);
			rows.add(new TeamPreviewRow(
					raw.rowNumber(),
					enrollment == null ? null : enrollment.getId(),
					text(raw.classCode()),
					text(raw.fullName()),
					studentCode,
					text(raw.email()),
					teamNo,
					teamName,
					role == null ? text(raw.teamRole()) : role.name(),
					action,
					List.copyOf(errors),
					List.copyOf(warnings)));
		}
		applyTeamAggregates(rows);
		List<String> blockingErrors = new ArrayList<>();
		for (CourseEnrollment enrollment : active) {
			if (!seenEnrollments.contains(enrollment.getId())) {
				StudentProfile profile = enrollment.getStudentProfile();
				String code = profile == null ? enrollment.getId().toString() : text(profile.getStudentCode());
				blockingErrors.add("ACTIVE student " + code + " is missing from the workbook.");
			}
		}
		for (TeamPreviewRow row : rows) {
			if (row.action() == TeamRowAction.INVALID || row.action() == TeamRowAction.CONFLICT) {
				blockingErrors.add("Row " + row.rowNumber() + " is blocking.");
			}
		}
		return new Classified(dedupe(blockingErrors), rows);
	}

	private void applyTeamAggregates(List<TeamPreviewRow> rows) {
		Map<Integer, List<Integer>> byTeamNo = new LinkedHashMap<>();
		Map<Integer, String> names = new LinkedHashMap<>();
		Map<String, Integer> nameOwners = new LinkedHashMap<>();
		Set<Integer> inconsistentNames = new LinkedHashSet<>();
		Set<Integer> duplicateNames = new LinkedHashSet<>();
		for (int i = 0; i < rows.size(); i++) {
			TeamPreviewRow row = rows.get(i);
			if (row.teamNo() == null) {
				continue;
			}
			byTeamNo.computeIfAbsent(row.teamNo(), key -> new ArrayList<>()).add(i);
			String nameKey = normalizeName(row.teamName());
			String existingName = names.putIfAbsent(row.teamNo(), nameKey);
			if (existingName != null && !existingName.equals(nameKey)) {
				inconsistentNames.add(row.teamNo());
			}
			if (StringUtils.hasText(nameKey)) {
				Integer owner = nameOwners.putIfAbsent(nameKey, row.teamNo());
				if (owner != null && !owner.equals(row.teamNo())) {
					duplicateNames.add(owner);
					duplicateNames.add(row.teamNo());
				}
			}
		}
		Set<Integer> leaderIssues = new LinkedHashSet<>();
		for (Map.Entry<Integer, List<Integer>> entry : byTeamNo.entrySet()) {
			int leaders = 0;
			for (Integer index : entry.getValue()) {
				if (RoleInTeam.LEADER.name().equals(rows.get(index).teamRole())) {
					leaders++;
				}
			}
			if (leaders != 1) {
				leaderIssues.add(entry.getKey());
			}
		}
		for (int i = 0; i < rows.size(); i++) {
			TeamPreviewRow row = rows.get(i);
			if (row.teamNo() == null) {
				continue;
			}
			List<String> errors = new ArrayList<>(row.errors());
			if (inconsistentNames.contains(row.teamNo())) {
				errors.add("TeamName does not match other rows with this TeamNo.");
			}
			if (duplicateNames.contains(row.teamNo())) {
				errors.add("TeamName is already used by a different TeamNo.");
			}
			if (leaderIssues.contains(row.teamNo())) {
				int leaders = 0;
				for (Integer index : byTeamNo.get(row.teamNo())) {
					if (RoleInTeam.LEADER.name().equals(rows.get(index).teamRole())) {
						leaders++;
					}
				}
				errors.add(leaders == 0 ? "Team is missing a Leader." : "Team has more than one Leader.");
			}
			if (errors.size() == row.errors().size()) {
				continue;
			}
			rows.set(i, withErrors(row, errors));
		}
	}

	private TeamPreviewRow withErrors(TeamPreviewRow row, List<String> errors) {
		return new TeamPreviewRow(
				row.rowNumber(),
				row.courseEnrollmentId(),
				row.classCode(),
				row.fullName(),
				row.studentCode(),
				row.email(),
				row.teamNo(),
				row.teamName(),
				row.teamRole(),
				actionForErrors(errors),
				List.copyOf(errors),
				row.warnings());
	}

	private void identityMismatch(
			CourseEnrollment enrollment, String expectedClass, LecturerTeamWorkbook.RawRow raw, List<String> errors) {
		StudentProfile profile = enrollment.getStudentProfile();
		UserAccount user = profile == null ? null : profile.getUserAccount();
		if (!expectedClass.equals(normalizeCode(raw.classCode()))) {
			errors.add("Class does not match this course.");
		}
		String expectedName = user == null ? "" : text(user.getFullName());
		if (!expectedName.equals(text(raw.fullName()))) {
			errors.add("FullName does not match the ACTIVE enrollment.");
		}
		String expectedEmail = user == null ? "" : text(user.getEmail());
		if (!expectedEmail.equalsIgnoreCase(text(raw.email()))) {
			errors.add("Email does not match the ACTIVE enrollment.");
		}
	}

	private TeamRowAction desiredAction(
			CourseEnrollment enrollment,
			Integer teamNo,
			RoleInTeam role,
			Map<UUID, TeamMember> memberships,
			Map<Integer, Team> existingTeams) {
		TeamMember current = memberships.get(enrollment.getId());
		boolean teamExists = existingTeams.containsKey(teamNo);
		if (current == null) {
			return teamExists ? TeamRowAction.READY_ASSIGN : TeamRowAction.READY_CREATE;
		}
		Team currentTeam = current.getTeam();
		Integer currentNo = currentTeam == null ? null : currentTeam.getTeamNo();
		if (teamNo.equals(currentNo) && role == current.getRoleInTeam()) {
			return TeamRowAction.ALREADY_ASSIGNED;
		}
		return TeamRowAction.READY_REASSIGN;
	}

	private TeamConfirmResponse applyConfirm(
			Course course, List<TeamPreviewRow> rows, UserAccount actor, AuditRequest auditRequest) {
		Map<Integer, Team> teams = new LinkedHashMap<>();
		for (Team team : store.listTeams(course.getId())) {
			if (team.getTeamNo() != null) {
				teams.put(team.getTeamNo(), team);
			}
		}
		int createdTeams = 0;
		int updatedTeams = 0;
		List<PendingAudit> pendingAudits = new ArrayList<>();
		Set<Integer> desiredTeamNos = new LinkedHashSet<>();
		Map<Integer, String> desiredNames = new LinkedHashMap<>();
		for (TeamPreviewRow row : rows) {
			if (row.teamNo() == null) {
				continue;
			}
			desiredTeamNos.add(row.teamNo());
			desiredNames.putIfAbsent(row.teamNo(), text(row.teamName()));
		}
		for (Integer teamNo : desiredTeamNos) {
			String name = desiredNames.get(teamNo);
			Team team = teams.get(teamNo);
			if (team == null) {
				team = new Team();
				team.setCourse(course);
				team.setProject(null);
				team.setTeamNo(teamNo);
				team.setName(name);
				team = store.saveTeam(team);
				teams.put(teamNo, team);
				createdTeams++;
				pendingAudits.add(new PendingAudit(
						team, TEAM_CREATED, "team", team.getId(), null, Map.of("teamNo", teamNo, "name", name)));
			} else if (!name.equals(text(team.getName()))) {
				Map<String, Object> before = Map.of("name", text(team.getName()));
				team.setName(name);
				store.saveTeam(team);
				updatedTeams++;
				pendingAudits.add(new PendingAudit(
						team, TEAM_RENAMED, "team", team.getId(), before, Map.of("name", name)));
			}
		}
		int assigned = 0;
		int reassigned = 0;
		int updatedRoles = 0;
		int unchanged = 0;
		int emailsEnqueued = 0;
		Map<UUID, TeamMember> memberships = membershipsByEnrollment(course.getId());
		Map<UUID, CourseEnrollment> activeById = new LinkedHashMap<>();
		for (CourseEnrollment enrollment : store.listActiveEnrollments(course.getId())) {
			activeById.put(enrollment.getId(), enrollment);
		}
		for (TeamPreviewRow row : rows) {
			if (row.action() == TeamRowAction.ALREADY_ASSIGNED) {
				unchanged++;
				continue;
			}
			if (row.courseEnrollmentId() == null || row.teamNo() == null || row.teamRole() == null) {
				throw new AcademicException(
						AcademicErrorCode.TEAM_CONFIRM_BLOCKED, HttpStatus.CONFLICT, "Team preview is no longer valid.");
			}
			CourseEnrollment enrollment = activeById.get(row.courseEnrollmentId());
			if (enrollment == null) {
				throw new AcademicException(
						AcademicErrorCode.TEAM_CONFIRM_BLOCKED,
						HttpStatus.CONFLICT,
						"ACTIVE enrollment changed after preview.");
			}
			Team team = teams.get(row.teamNo());
			RoleInTeam role = RoleInTeam.valueOf(row.teamRole());
			TeamMember member = memberships.get(enrollment.getId());
			if (member == null) {
				member = new TeamMember();
				member.setTeam(team);
				member.setCourse(course);
				member.setCourseEnrollment(enrollment);
				member.setRoleInTeam(role);
				store.saveMember(member);
				memberships.put(enrollment.getId(), member);
				assigned++;
				emailsEnqueued += enqueueAssigned(course, enrollment, team, role);
				pendingAudits.add(new PendingAudit(
						team,
						TEAM_MEMBER_ASSIGNED,
						"team_member",
						member.getId(),
						null,
						Map.of("teamNo", team.getTeamNo(), "role", role.name())));
				continue;
			}
			Integer previousNo = member.getTeam() == null ? null : member.getTeam().getTeamNo();
			RoleInTeam previousRole = member.getRoleInTeam();
			boolean moved = previousNo == null || !previousNo.equals(team.getTeamNo());
			boolean roleChanged = previousRole != role;
			if (!moved && !roleChanged) {
				unchanged++;
				continue;
			}
			Map<String, Object> before = new LinkedHashMap<>();
			before.put("teamNo", previousNo);
			before.put("role", previousRole == null ? null : previousRole.name());
			member.setTeam(team);
			member.setCourse(course);
			member.setRoleInTeam(role);
			store.saveMember(member);
			if (moved) {
				reassigned++;
				pendingAudits.add(new PendingAudit(
						team,
						TEAM_MEMBER_REASSIGNED,
						"team_member",
						member.getId(),
						before,
						Map.of("teamNo", team.getTeamNo(), "role", role.name())));
			} else {
				updatedRoles++;
				pendingAudits.add(new PendingAudit(
						team,
						TEAM_MEMBER_ROLE_CHANGED,
						"team_member",
						member.getId(),
						before,
						Map.of("teamNo", team.getTeamNo(), "role", role.name())));
			}
			emailsEnqueued += enqueueAssigned(course, enrollment, team, role);
		}
		assertOneLeader(desiredTeamNos, teams, memberships);
		pendingAudits.add(new PendingAudit(
				null,
				TEAM_ASSIGNMENT_IMPORTED,
				"course",
				course.getId(),
				null,
				Map.of(
						"createdTeams",
						createdTeams,
						"updatedTeams",
						updatedTeams,
						"assignedMembers",
						assigned,
						"reassignedMembers",
						reassigned,
						"updatedRoles",
						updatedRoles,
						"unchanged",
						unchanged,
						"emailsEnqueued",
						emailsEnqueued)));
		for (PendingAudit pending : pendingAudits) {
			record(
					actor,
					pending.team(),
					pending.action(),
					pending.entityType(),
					pending.entityId(),
					pending.before(),
					pending.after(),
					auditRequest);
		}
		return new TeamConfirmResponse(
				course.getId(),
				createdTeams,
				updatedTeams,
				assigned,
				reassigned,
				updatedRoles,
				unchanged,
				emailsEnqueued,
				LocalDateTime.now());
	}

	private void assertOneLeader(Set<Integer> teamNos, Map<Integer, Team> teams, Map<UUID, TeamMember> memberships) {
		for (Integer teamNo : teamNos) {
			Team team = teams.get(teamNo);
			int leaders = 0;
			for (TeamMember member : memberships.values()) {
				CourseEnrollment enrollment = member.getCourseEnrollment();
				boolean active = enrollment != null && enrollment.getEnrollmentStatus() == EnrollmentStatus.ACTIVE;
				if (active
						&& member.getTeam() != null
						&& team != null
						&& team.getId() != null
						&& team.getId().equals(member.getTeam().getId())
						&& member.getRoleInTeam() == RoleInTeam.LEADER) {
					leaders++;
				}
			}
			if (leaders != 1) {
				throw new AcademicException(
						AcademicErrorCode.TEAM_LEADER_INVALID,
						HttpStatus.CONFLICT,
						"Each team must have exactly one Leader.");
			}
		}
	}

	private int enqueueAssigned(Course course, CourseEnrollment enrollment, Team team, RoleInTeam role) {
		StudentProfile profile = enrollment.getStudentProfile();
		UserAccount user = profile == null ? null : profile.getUserAccount();
		if (user == null || !StringUtils.hasText(user.getEmail())) {
			return 0;
		}
		emails.enqueue(new EmailEnqueueRequest(
				user.getEmail(),
				user.getId(),
				"TEAM_ASSIGNED",
				EmailTemplateService.TEAM_ASSIGNED,
				templates.payload(
						EmailTemplateService.TEAM_ASSIGNED,
						EmailTemplateModel.teamAssigned(
								text(user.getFullName()),
								user.getEmail(),
								course.getName(),
								course.getSubject() == null ? course.getCourseCode() : course.getSubject().getSubjectCode(),
								classCode(course),
								course.getSemester() == null ? null : course.getSemester().getCode(),
								course.getSemester() == null ? null : course.getSemester().getName(),
								team.getTeamNo(),
								team.getName(),
								displayRole(role))),
				null));
		return 1;
	}

	private void record(
			UserAccount actor,
			Team team,
			String action,
			String entityType,
			UUID entityId,
			Map<String, Object> before,
			Map<String, Object> after,
			AuditRequest auditRequest) {
		audit.record(
				actor,
				null,
				team,
				action,
				entityType,
				entityId,
				before,
				after,
				Map.of(),
				AuditSource.API,
				auditRequest == null ? null : auditRequest.requestId(),
				auditRequest == null ? null : auditRequest.ip(),
				auditRequest == null ? null : auditRequest.userAgent());
	}

	private Map<UUID, TeamMember> membershipsByEnrollment(UUID courseId) {
		Map<UUID, TeamMember> memberships = new LinkedHashMap<>();
		for (TeamMember member : store.listMembers(courseId)) {
			if (member.getCourseEnrollment() != null) {
				memberships.put(member.getCourseEnrollment().getId(), member);
			}
		}
		return memberships;
	}

	private LecturerTeamMemberResponse toLecturerMember(TeamMember member) {
		CourseEnrollment enrollment = member.getCourseEnrollment();
		StudentProfile profile = enrollment == null ? null : enrollment.getStudentProfile();
		UserAccount user = profile == null ? null : profile.getUserAccount();
		return new LecturerTeamMemberResponse(
				enrollment == null ? null : enrollment.getId(),
				profile == null ? null : profile.getId(),
				profile == null ? null : profile.getStudentCode(),
				user == null ? null : user.getFullName(),
				user == null ? null : user.getEmail(),
				member.getRoleInTeam() == null ? null : member.getRoleInTeam().name());
	}

	private static List<TeamMember> sortMembers(List<TeamMember> members) {
		return members.stream()
				.sorted(Comparator.comparing((TeamMember member) -> member.getRoleInTeam() != RoleInTeam.LEADER)
						.thenComparing(member -> {
							CourseEnrollment enrollment = member.getCourseEnrollment();
							StudentProfile profile = enrollment == null ? null : enrollment.getStudentProfile();
							return profile == null || profile.getStudentCode() == null ? "" : profile.getStudentCode();
						}, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	private TeamPreviewResponse toPreviewResponse(String token, Course course, Classified classified) {
		boolean blocking = hasBlockingErrors(classified.blockingErrors(), classified.rows());
		return new TeamPreviewResponse(
				token,
				course.getId(),
				classCode(course),
				blocking,
				classified.blockingErrors(),
				summary(classified.rows(), classified.blockingErrors()),
				classified.rows());
	}

	private <T> T writeAtomic(Supplier<T> action) {
		if (transactionManager != null) {
			return new TransactionTemplate(transactionManager).execute(status -> action.get());
		}
		return store.inTransaction(action);
	}

	private String newToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static TeamPreviewSummary summary(List<TeamPreviewRow> rows, List<String> blockingErrors) {
		int invalid = 0;
		int create = 0;
		int assign = 0;
		int reassign = 0;
		int already = 0;
		for (TeamPreviewRow row : rows) {
			switch (row.action()) {
				case READY_CREATE -> create++;
				case READY_ASSIGN -> assign++;
				case READY_REASSIGN -> reassign++;
				case ALREADY_ASSIGNED -> already++;
				case INVALID, CONFLICT -> invalid++;
			}
		}
		return new TeamPreviewSummary(
				rows.size(),
				rows.size() - invalid,
				invalid,
				create,
				assign,
				reassign,
				already,
				blockingErrors.size());
	}

	private static boolean hasBlockingErrors(List<String> blockingErrors, List<TeamPreviewRow> rows) {
		if (blockingErrors != null && !blockingErrors.isEmpty()) {
			return true;
		}
		return rows.stream()
				.anyMatch(row -> row.action() == TeamRowAction.INVALID || row.action() == TeamRowAction.CONFLICT);
	}

	private static TeamRowAction actionForErrors(List<String> errors) {
		boolean conflict = errors.stream()
				.anyMatch(error -> error.contains("Duplicate")
						|| error.contains("does not match")
						|| error.contains("Leader")
						|| error.contains("already used")
						|| error.contains("ACTIVE enrollment"));
		return conflict ? TeamRowAction.CONFLICT : TeamRowAction.INVALID;
	}

	private static Integer parseTeamNo(String raw, List<String> errors) {
		String value = text(raw);
		if (!StringUtils.hasText(value)) {
			errors.add("TeamNo is required.");
			return null;
		}
		String normalized = value.matches("\\d+\\.0+") ? value.substring(0, value.indexOf('.')) : value;
		if (!normalized.matches("\\d+")) {
			errors.add("TeamNo must be a positive integer.");
			return null;
		}
		try {
			int teamNo = Integer.parseInt(normalized);
			if (teamNo < 1) {
				errors.add("TeamNo must be a positive integer.");
				return null;
			}
			return teamNo;
		} catch (NumberFormatException ex) {
			errors.add("TeamNo must be a positive integer.");
			return null;
		}
	}

	private static RoleInTeam parseRole(String raw, List<String> errors) {
		String value = text(raw);
		if (!StringUtils.hasText(value)) {
			errors.add("TeamRole is required.");
			return null;
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		if ("LEADER".equals(normalized)) {
			return RoleInTeam.LEADER;
		}
		if ("MEMBER".equals(normalized)) {
			return RoleInTeam.MEMBER;
		}
		if ("MENTOR".equals(normalized)) {
			errors.add("TeamRole MENTOR is not valid for Lecturer Team V1.");
			return null;
		}
		errors.add("TeamRole must be Leader or Member.");
		return null;
	}

	private static String displayRole(RoleInTeam role) {
		if (role == RoleInTeam.LEADER) {
			return LecturerTeamWorkbook.ROLE_LEADER;
		}
		if (role == RoleInTeam.MEMBER) {
			return LecturerTeamWorkbook.ROLE_MEMBER;
		}
		return "";
	}

	private static String classCode(Course course) {
		return course.getAcademicClass() == null ? "" : text(course.getAcademicClass().getClassCode());
	}

	private static String normalizeCode(String value) {
		return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
	}

	private static String normalizeName(String value) {
		return text(value).toLowerCase(Locale.ROOT);
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}

	private static List<String> dedupe(List<String> values) {
		return new ArrayList<>(new LinkedHashSet<>(values));
	}

	private record Classified(List<String> blockingErrors, List<TeamPreviewRow> rows) {}

	private record PendingAudit(
			Team team,
			String action,
			String entityType,
			UUID entityId,
			Map<String, Object> before,
			Map<String, Object> after) {}
}
