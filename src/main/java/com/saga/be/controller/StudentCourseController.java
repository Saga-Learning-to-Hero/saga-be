package com.saga.be.controller;

import com.saga.be.dto.student.StudentCoursePageResponse;
import com.saga.be.dto.student.StudentCourseResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.student.StudentCourseService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/student/courses")
@Tag(name = "Student courses", description = "The authenticated student's enrolled courses.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentCourseController {

	private final StudentCourseService courses;

	public StudentCourseController(StudentCourseService courses) {
		this.courses = courses;
	}

	@GetMapping
	@Operation(
			summary = "List the authenticated student's ACTIVE enrolled courses.",
			description = "Unpaged array for the course switcher/context. Course pickers should use GET /api/student/courses/paged.")
	public List<StudentCourseResponse> listMine(@AuthenticationPrincipal SagaUserPrincipal principal) {
		return courses.listMine(principal.getUserId());
	}

	@GetMapping("/paged")
	@Operation(
			summary = "Paged ACTIVE enrolled courses for the course picker.",
			description =
					"Same items and order as the unpaged list (newest semester first). page default 0, size default 50, "
							+ "size max 200. Filters: semesterId; search (case-insensitive, matches course code, subject "
							+ "code/name, class code, semester code). Invalid page/size -> 400 REQUEST_INVALID.")
	public StudentCoursePageResponse listMinePaged(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam(required = false) UUID semesterId,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return courses.listMinePaged(principal.getUserId(), semesterId, search, page, size);
	}
}
