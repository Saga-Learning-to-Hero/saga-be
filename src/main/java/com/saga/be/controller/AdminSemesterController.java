package com.saga.be.controller;

import com.saga.be.dto.academic.CreateSemesterRequest;
import com.saga.be.dto.academic.PatchSemesterRequest;
import com.saga.be.dto.academic.SemesterResponse;
import com.saga.be.dto.academic.SetActiveSemesterRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/semesters")
@Tag(name = "Admin semesters", description = "Academic term catalog and the platform active-semester setting. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminSemesterController {

	private final AcademicRuntimeService runtime;
	private final UserAccountRepository users;

	public AdminSemesterController(AcademicRuntimeService runtime, UserAccountRepository users) {
		this.runtime = runtime;
		this.users = users;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a semester")
	public SemesterResponse create(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody CreateSemesterRequest request,
			HttpServletRequest http) {
		return runtime.createSemester(request, actor(principal), audit(http));
	}

	@GetMapping
	@Operation(summary = "List semesters")
	public List<SemesterResponse> list() {
		return runtime.listSemesters();
	}

	@GetMapping("/active")
	@Operation(summary = "Get the platform active semester setting")
	public SemesterResponse getActive() {
		return runtime.getActiveSemester();
	}

	@PutMapping("/active")
	@Operation(summary = "Set the platform active semester")
	public SemesterResponse setActive(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody SetActiveSemesterRequest request,
			HttpServletRequest http) {
		return runtime.setActiveSemester(request, actor(principal), audit(http));
	}

	@GetMapping("/{semesterId}")
	@Operation(summary = "Get a semester")
	public SemesterResponse get(@PathVariable UUID semesterId) {
		return runtime.getSemester(semesterId);
	}

	@PatchMapping("/{semesterId}")
	@Operation(summary = "Update semester code, name, or dates")
	public SemesterResponse patch(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID semesterId,
			@Valid @RequestBody PatchSemesterRequest request,
			HttpServletRequest http) {
		return runtime.updateSemester(semesterId, request, actor(principal), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
