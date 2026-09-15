package com.saga.be.controller;

import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.service.admin.AdminUserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@Tag(
		name = "Admin users",
		description =
				"Read-only STUDENT and LECTURER directory. ADMIN accounts are excluded. Future status/role "
						+ "mutations on this surface must also hide ADMIN targets unless a separate admin-management "
						+ "policy is introduced.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminUserController {

	private final AdminUserQueryService users;

	public AdminUserController(AdminUserQueryService users) {
		this.users = users;
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
}
