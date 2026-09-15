package com.saga.be.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.AdminAuditLogPageResponse;
import com.saga.be.dto.admin.AdminAuditLogResponse;
import com.saga.be.repository.AdminAuditLogQueryRow;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.web.RequestTiming;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class AdminAuditLogQueryService {

	private final AuditLogRepository auditLogs;
	private final ObjectMapper mapper;

	public AdminAuditLogQueryService(AuditLogRepository auditLogs, ObjectMapper mapper) {
		this.auditLogs = auditLogs;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true)
	public AdminAuditLogPageResponse list(
			UUID actorUserId,
			String action,
			String entityType,
			UUID entityId,
			LocalDateTime from,
			LocalDateTime to,
			Integer page,
			Integer size) {
		return RequestTiming.record("listAdminAuditLogs", () -> {
			if (from != null && to != null && from.isAfter(to)) {
				throw AdminPaging.invalidRequest("from must be before or equal to to.");
			}
			int pageNumber = AdminPaging.page(page);
			int pageSize = AdminPaging.size(size);
			Page<AdminAuditLogQueryRow> rows = auditLogs.searchAdminAuditLogs(
					actorUserId,
					blankToNull(action),
					blankToNull(entityType),
					entityId,
					from,
					to,
					PageRequest.of(pageNumber, pageSize));
			return new AdminAuditLogPageResponse(
					rows.getContent().stream().map(this::toResponse).toList(),
					pageNumber,
					pageSize,
					rows.getTotalElements());
		});
	}

	private AdminAuditLogResponse toResponse(AdminAuditLogQueryRow row) {
		return new AdminAuditLogResponse(
				row.id(),
				row.occurredAt(),
				row.actorUserId(),
				row.actorFullNameSnapshot(),
				row.actorRoleSnapshot(),
				row.actorEmailSnapshot(),
				row.actorStudentCodeSnapshot(),
				row.contextClassId(),
				row.contextClassCodeSnapshot(),
				row.contextClassNameSnapshot(),
				row.contextCourseId(),
				row.contextTeamId(),
				row.contextProjectId(),
				row.action(),
				row.entityType(),
				row.entityId(),
				parseJson(row.beforeData()),
				parseJson(row.afterData()),
				parseJson(row.metadataJson()),
				row.source() == null ? null : row.source().name(),
				row.requestId(),
				row.ipAddress(),
				row.userAgent());
	}

	private Object parseJson(String json) {
		if (!StringUtils.hasText(json)) {
			return null;
		}
		try {
			return mapper.readValue(json, Object.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}
}
