package com.saga.be.controller;

import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.dto.academic.CreateCourseRequest;
import com.saga.be.dto.academic.PatchCourseRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.academic.AcademicRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/courses")
@Tag(
		name = "Admin courses",
		description = "Runtime teaching offering: class + subject + pinned PUBLISHED syllabus + lecturer. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminCourseController {

	private final AcademicRuntimeService runtime;
	private final UserAccountRepository users;

	public AdminCourseController(AcademicRuntimeService runtime, UserAccountRepository users) {
		this.runtime = runtime;
		this.users = users;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a course offering pinned to a published syllabus")
	public CourseResponse create(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody CreateCourseRequest request,
			HttpServletRequest http) {
		return runtime.createCourse(request, actor(principal), audit(http));
	}

	@GetMapping
	@Operation(summary = "List courses")
	public List<CourseResponse> list(
			@RequestParam(required = false) UUID semesterId,
			@RequestParam(required = false) UUID academicClassId,
			@RequestParam(required = false) UUID subjectId,
			@RequestParam(required = false) UUID lecturerId) {
		return runtime.listCourses(semesterId, academicClassId, subjectId, lecturerId);
	}

	@GetMapping("/{courseId}")
	@Operation(summary = "Get a course with class, semester, subject, syllabus, and lecturer")
	public CourseResponse get(@PathVariable UUID courseId) {
		return runtime.getCourse(courseId);
	}

	@PatchMapping("/{courseId}")
	@Operation(summary = "Update course name, lecturer, or syllabus pin when no downstream data exists")
	public CourseResponse patch(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody PatchCourseRequest request,
			HttpServletRequest http) {
		return runtime.updateCourse(courseId, request, actor(principal), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
