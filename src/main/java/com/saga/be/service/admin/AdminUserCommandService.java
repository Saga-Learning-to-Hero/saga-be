package com.saga.be.service.admin;

import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.web.RequestTiming;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * ADMIN mutations for STUDENT and LECTURER accounts only. ADMIN targets are hidden with the same
 * 404 as a missing user. Role changes are out of scope here.
 */
@Service
@Profile("!test")
public class AdminUserCommandService {

	public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
	public static final String ENTITY_TYPE = "user_account";

	private final UserAccountRepository users;
	private final AdminUserQueryService queries;
	private final AuditService audit;

	public AdminUserCommandService(
			UserAccountRepository users, AdminUserQueryService queries, AuditService audit) {
		this.users = users;
		this.queries = queries;
		this.audit = audit;
	}

	@Transactional
	public AdminUserResponse updateStatus(UUID actorId, UUID targetId, String rawStatus, AuditRequest auditRequest) {
		return RequestTiming.record("updateAdminUserStatus", () -> {
			AccountStatus requested = parseMutableStatus(rawStatus);
			UserAccount actor = users.findById(actorId)
					.orElseThrow(() -> new AcademicException(
							AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
			UserAccount target = users.findByIdForUpdate(targetId).orElseThrow(AdminUserCommandService::notFound);
			if (target.getAccountRole() == AccountRole.ADMIN) {
				throw notFound();
			}
			if (target.getAccountStatus() == requested) {
				return queries.get(target.getId());
			}
			Map<String, Object> before = Map.of("accountStatus", target.getAccountStatus().name());
			target.setAccountStatus(requested);
			users.save(target);
			audit.record(
					actor,
					null,
					null,
					USER_STATUS_CHANGED,
					ENTITY_TYPE,
					target.getId(),
					before,
					Map.of("accountStatus", requested.name()),
					Map.of("role", target.getAccountRole().name()),
					AuditSource.API,
					auditRequest == null ? null : auditRequest.requestId(),
					auditRequest == null ? null : auditRequest.ip(),
					auditRequest == null ? null : auditRequest.userAgent());
			return queries.get(target.getId());
		});
	}

	static AccountStatus parseMutableStatus(String status) {
		if (!StringUtils.hasText(status)) {
			throw AdminPaging.invalidRequest("status is invalid.");
		}
		String normalized = status.trim().toUpperCase(Locale.ROOT);
		if ("ACTIVE".equals(normalized)) {
			return AccountStatus.ACTIVE;
		}
		if ("INACTIVE".equals(normalized)) {
			return AccountStatus.INACTIVE;
		}
		throw AdminPaging.invalidRequest("status is invalid.");
	}

	private static AcademicException notFound() {
		return new AcademicException(
				AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found.");
	}
}
