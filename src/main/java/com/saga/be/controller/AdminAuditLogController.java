package com.saga.be.controller;

import com.saga.be.dto.admin.AdminAuditLogPageResponse;
import com.saga.be.service.admin.AdminAuditLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "Admin audit logs", description = "Read-only audit log listing. ADMIN only. No create, update, or delete.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminAuditLogController {

	private final AdminAuditLogQueryService auditLogs;

	public AdminAuditLogController(AdminAuditLogQueryService auditLogs) {
		this.auditLogs = auditLogs;
	}

	@GetMapping
	@Operation(summary = "List audit logs with optional actor, action, resource, and time filters")
	public AdminAuditLogPageResponse list(
			@RequestParam(required = false) UUID actorUserId,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String entityType,
			@RequestParam(required = false) UUID entityId,
			@RequestParam(required = false) LocalDateTime from,
			@RequestParam(required = false) LocalDateTime to,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return auditLogs.list(actorUserId, action, entityType, entityId, from, to, page, size);
	}
}
