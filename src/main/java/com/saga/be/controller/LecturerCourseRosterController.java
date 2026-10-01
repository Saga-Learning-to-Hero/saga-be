package com.saga.be.controller;

import com.saga.be.dto.roster.CourseRosterEntryResponse;
import com.saga.be.dto.roster.StudentRemovalRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.roster.CourseRosterService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/lecturer/courses/{courseId}/roster")
@Tag(name = "Lecturer roster", description = "Roster actions the course's own lecturer may take.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseRosterController {

	private final LecturerCourseAuthorization authorization;
	private final CourseRosterService roster;
	private final UserAccountRepository users;

	public LecturerCourseRosterController(
			LecturerCourseAuthorization authorization, CourseRosterService roster, UserAccountRepository users) {
		this.authorization = authorization;
		this.roster = roster;
		this.users = users;
	}

	@DeleteMapping("/enrollments/{enrollmentId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@ResponseStatus(HttpStatus.OK)
	@Operation(
			summary = "Withdraw a student from the course (assigned lecturer)",
			description =
					"""
					Same behavior as the ADMIN roster removal: the enrollment becomes WITHDRAWN and the \
					student leaves their team; nothing is deleted. Only the lecturer assigned to this \
					course (or ADMIN) may call it, else 403 LECTURER_COURSE_FORBIDDEN. JSON body \
					{"reason": "..."} is required (1-500 chars, else 400 REQUEST_INVALID); the student \
					receives it by in-app notification and email. A team Leader must be replaced first \
					(409 TEAM_LEADER_REMOVAL_REQUIRES_REASSIGNMENT); an already-withdrawn enrollment is \
					409 ROSTER_STUDENT_ALREADY_REMOVED.
					""")
	public CourseRosterEntryResponse removeEnrollment(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID enrollmentId,
			@Valid @RequestBody StudentRemovalRequest request,
			HttpServletRequest http) {
		UserAccount actor = users.findById(principal.getUserId()).orElseThrow();
		authorization.requireCourse(actor, courseId);
		return roster.removeEnrollment(courseId, enrollmentId, actor, request.reason(), audit(http));
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
