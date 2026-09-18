package com.saga.be.controller;

import com.saga.be.dto.academic.AcademicClassPageResponse;
import com.saga.be.dto.academic.AcademicClassResponse;
import com.saga.be.dto.academic.CreateAcademicClassRequest;
import com.saga.be.dto.academic.PatchAcademicClassRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.academic.AcademicRuntimeService;
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
@RequestMapping("/api/admin/classes")
@Tag(name = "Admin classes", description = "Academic classes belong to a semester. ADMIN only. Not a course offering.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminAcademicClassController {

	private final AcademicRuntimeService runtime;
	private final UserAccountRepository users;

	public AdminAcademicClassController(AcademicRuntimeService runtime, UserAccountRepository users) {
		this.runtime = runtime;
		this.users = users;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an academic class in a semester")
	public AcademicClassResponse create(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody CreateAcademicClassRequest request,
			HttpServletRequest http) {
		return runtime.createClass(request, actor(principal), audit(http));
	}

	@GetMapping
	@Workload(WorkloadClass.INTERACTIVE_NORMAL)
	@Operation(
			summary = "Paged academic classes. ADMIN only.",
			description = "page default 0, size default 50, size max 200. Filter: semesterId.")
	public AcademicClassPageResponse list(
			@RequestParam(required = false) UUID semesterId,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return runtime.listClasses(semesterId, page, size);
	}

	@GetMapping("/{classId}")
	@Operation(summary = "Get an academic class")
	public AcademicClassResponse get(@PathVariable UUID classId) {
		return runtime.getAcademicClass(classId);
	}

	@PatchMapping("/{classId}")
	@Operation(summary = "Update class code or name")
	public AcademicClassResponse patch(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID classId,
			@Valid @RequestBody PatchAcademicClassRequest request,
			HttpServletRequest http) {
		return runtime.updateClass(classId, request, actor(principal), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
