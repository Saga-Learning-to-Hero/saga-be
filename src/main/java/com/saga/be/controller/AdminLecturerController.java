package com.saga.be.controller;

import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.service.academic.AdminLecturerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/lecturers")
@Tag(
		name = "Admin lecturers",
		description = "Lecturer directory for course assignment. lecturerProfileId is Course.lecturerId, not userId. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminLecturerController {

	private final AdminLecturerService lecturers;

	public AdminLecturerController(AdminLecturerService lecturers) {
		this.lecturers = lecturers;
	}

	@GetMapping
	@Operation(
			summary = "List lecturers for Admin management and Course lecturer dropdown",
			description =
					"Default list is assignable lecturers only (LECTURER + ACTIVE), matching COURSE_LECTURER_INVALID. "
							+ "Use lecturerProfileId as CreateCourseRequest.lecturerId. Do not send userId.")
	public List<LecturerDirectoryResponse> list(
			@RequestParam(required = false) Boolean active, @RequestParam(required = false) String search) {
		return lecturers.list(active, search);
	}
}
