package com.saga.be.controller;

import com.saga.be.dto.project.ProjectTypeResponse;
import com.saga.be.service.student.StudentProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/student/project-types")
@Tag(name = "Student project types", description = "Seeded ProjectType catalog for Team Leader project setup.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentProjectTypeController {

	private final StudentProjectService projects;

	public StudentProjectTypeController(StudentProjectService projects) {
		this.projects = projects;
	}

	@GetMapping
	@Operation(summary = "List seeded project types")
	public List<ProjectTypeResponse> list() {
		return projects.listTypes();
	}
}
