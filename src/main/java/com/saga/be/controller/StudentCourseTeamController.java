package com.saga.be.controller;

import com.saga.be.dto.team.StudentTeamResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.student.StudentTeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/student/courses/{courseId}/team")
@Tag(name = "Student team", description = "The authenticated student's team in an enrolled course.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentCourseTeamController {

	private final StudentTeamService teams;

	public StudentCourseTeamController(StudentTeamService teams) {
		this.teams = teams;
	}

	@GetMapping
	@Operation(summary = "Get the current student's team in a course")
	public StudentTeamResponse myTeam(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return teams.myTeam(principal.getUserId(), courseId);
	}
}
