package com.saga.be.controller;

import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.dto.team.LecturerActiveRosterResponse;
import com.saga.be.dto.team.LecturerCourseProgressResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.lecturer.LecturerCourseService;
import com.saga.be.service.lecturer.LecturerProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/lecturer/courses")
@Tag(name = "Lecturer courses", description = "Courses assigned to the authenticated lecturer. ADMIN may access for support.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseController {

	private final LecturerCourseService courses;
	private final UserAccountRepository users;
	private final LecturerProgressService progress;

	public LecturerCourseController(
			LecturerCourseService courses, UserAccountRepository users, LecturerProgressService progress) {
		this.courses = courses;
		this.users = users;
		this.progress = progress;
	}

	@GetMapping
	@Operation(summary = "List courses assigned to the current lecturer")
	public List<CourseResponse> list(@AuthenticationPrincipal SagaUserPrincipal principal) {
		return courses.listCourses(actor(principal));
	}

	@GetMapping("/{courseId}")
	@Operation(summary = "Get an assigned course")
	public CourseResponse get(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return courses.getCourse(actor(principal), courseId);
	}

	@GetMapping("/{courseId}/roster")
	@Operation(summary = "List ACTIVE enrollments for an assigned course")
	public LecturerActiveRosterResponse roster(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return courses.getActiveRoster(actor(principal), courseId);
	}

	@GetMapping("/{courseId}/progress")
	@Operation(
			summary = "Lightweight Task/Sprint/Commit progress summary per Team in this course, for dashboard cards.",
			description =
					"Bounded, constant-query-count aggregation — not per-team live provider calls. "
							+ "Click a team's projectId into GET /api/projects/{projectId}/progress for full detail.")
	public LecturerCourseProgressResponse progress(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return progress.getCourseProgress(actor(principal), courseId);
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}
}
