package com.saga.be.controller;

import com.saga.be.dto.student.StudentCourseResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.student.StudentCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/student/courses")
@Tag(name = "Student courses", description = "The authenticated student's enrolled courses.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentCourseController {

	private final StudentCourseService courses;

	public StudentCourseController(StudentCourseService courses) {
		this.courses = courses;
	}

	@GetMapping
	@Operation(summary = "List the authenticated student's ACTIVE enrolled courses.")
	public List<StudentCourseResponse> listMine(@AuthenticationPrincipal SagaUserPrincipal principal) {
		return courses.listMine(principal.getUserId());
	}
}
