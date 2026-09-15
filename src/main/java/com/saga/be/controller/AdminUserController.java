package com.saga.be.controller;

import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.dto.admin.PatchAdminUserStatusRequest;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.admin.AdminUserCommandService;
import com.saga.be.service.admin.AdminUserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@Tag(
		name = "Admin users",
		description =
				"STUDENT and LECTURER directory. ADMIN accounts are excluded from list, detail, and status "
						+ "changes. Status PATCH accepts ACTIVE and INACTIVE only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminUserController {

	private final AdminUserQueryService users;
	private final AdminUserCommandService commands;

	public AdminUserController(AdminUserQueryService users, AdminUserCommandService commands) {
		this.users = users;
		this.commands = commands;
	}

	@GetMapping
	@Operation(summary = "List users with optional search, role, and status filters")
	public AdminUserPageResponse list(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String role,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return users.list(q, role, status, page, size);
	}

	@GetMapping("/{userId}")
	@Operation(summary = "Get one user account and profile summary")
	public AdminUserResponse get(@PathVariable UUID userId) {
		return users.get(userId);
	}

	@PatchMapping("/{userId}/status")
	@Operation(summary = "Activate or deactivate a STUDENT or LECTURER account")
	public AdminUserResponse patchStatus(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID userId,
			@Valid @RequestBody PatchAdminUserStatusRequest request,
			HttpServletRequest http) {
		return commands.updateStatus(principal.getUserId(), userId, request.status(), audit(http));
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
