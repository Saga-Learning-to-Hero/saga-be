package com.saga.be.controller;

import com.saga.be.dto.roster.AddRosterStudentRequest;
import com.saga.be.dto.roster.RosterStudentMutationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.roster.CourseRosterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/courses/{courseId}")
@Tag(name = "Admin course students", description = "Manual add/remove of one student in a course. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminCourseStudentController {

	private final CourseRosterService roster;
	private final UserAccountRepository users;

	public AdminCourseStudentController(CourseRosterService roster, UserAccountRepository users) {
		this.roster = roster;
		this.users = users;
	}

	@PostMapping("/students")
	@Operation(summary = "Manually add one student to a course")
	public ResponseEntity<RosterStudentMutationResponse> addStudent(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody AddRosterStudentRequest request,
			HttpServletRequest http) {
		RosterStudentMutationResponse body = roster.addStudent(courseId, request, actor(principal), audit(http));
		HttpStatus status = CourseRosterService.ACTION_ENROLLED.equals(body.action())
						|| CourseRosterService.ACTION_INVITED.equals(body.action())
				? HttpStatus.CREATED
				: HttpStatus.OK;
		return ResponseEntity.status(status).body(body);
	}

	@DeleteMapping("/enrollments/{enrollmentId}")
	@Operation(summary = "Withdraw an ACTIVE enrollment from a course")
	public RosterStudentMutationResponse removeEnrollment(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID enrollmentId,
			HttpServletRequest http) {
		return roster.removeEnrollment(courseId, enrollmentId, actor(principal), audit(http));
	}

	@DeleteMapping("/invitations/{invitationId}")
	@Operation(summary = "Cancel an outstanding course invitation")
	public RosterStudentMutationResponse removeInvitation(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID invitationId,
			HttpServletRequest http) {
		return roster.removeInvitation(courseId, invitationId, actor(principal), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
