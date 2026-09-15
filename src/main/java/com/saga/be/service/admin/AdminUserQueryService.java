package com.saga.be.service.admin;

import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AdminUserQueryRow;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.web.RequestTiming;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * ADMIN-only directory of STUDENT and LECTURER accounts. ADMIN rows are never listed or
 * returned by id. Future PATCH status/role APIs on this surface must reject or hide ADMIN
 * targets the same way unless a separate admin-management policy is introduced.
 */
@Service
@Profile("!test")
public class AdminUserQueryService {

	private final UserAccountRepository users;

	public AdminUserQueryService(UserAccountRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public AdminUserPageResponse list(String q, String role, String status, Integer page, Integer size) {
		return RequestTiming.record("listAdminUsers", () -> {
			int pageNumber = AdminPaging.page(page);
			int pageSize = AdminPaging.size(size);
			Page<AdminUserQueryRow> rows = users.searchAdminUsers(
					parseRole(role), parseStatus(status), likePattern(q), PageRequest.of(pageNumber, pageSize));
			return new AdminUserPageResponse(
					rows.getContent().stream().map(AdminUserQueryService::toResponse).toList(),
					pageNumber,
					pageSize,
					rows.getTotalElements());
		});
	}

	@Transactional(readOnly = true)
	public AdminUserResponse get(UUID userId) {
		return RequestTiming.record("getAdminUser", () -> users.findAdminUserById(userId)
				.map(AdminUserQueryService::toResponse)
				.orElseThrow(AdminUserQueryService::notFound));
	}

	private static AdminUserResponse toResponse(AdminUserQueryRow row) {
		return new AdminUserResponse(
				row.id(),
				row.email(),
				row.username(),
				row.fullName(),
				row.avatarUrl(),
				row.accountRole() == null ? null : row.accountRole().name(),
				row.accountStatus() == null ? null : row.accountStatus().name(),
				row.studentCode(),
				row.lecturerProfileId(),
				row.createdAt());
	}

	private static AccountRole parseRole(String role) {
		if (!StringUtils.hasText(role)) {
			return null;
		}
		try {
			AccountRole parsed = AccountRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
			if (parsed == AccountRole.ADMIN) {
				throw AdminPaging.invalidRequest("role is invalid.");
			}
			return parsed;
		} catch (IllegalArgumentException ex) {
			throw AdminPaging.invalidRequest("role is invalid.");
		}
	}

	private static AccountStatus parseStatus(String status) {
		if (!StringUtils.hasText(status)) {
			return null;
		}
		try {
			return AccountStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw AdminPaging.invalidRequest("status is invalid.");
		}
	}

	static String likePattern(String q) {
		if (!StringUtils.hasText(q)) {
			return null;
		}
		String trimmed = q.trim();
		if (trimmed.length() > AdminPaging.SEARCH_MAX_LENGTH) {
			trimmed = trimmed.substring(0, AdminPaging.SEARCH_MAX_LENGTH);
		}
		String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
	}

	private static AcademicException notFound() {
		return new AcademicException(
				AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found.");
	}
}
