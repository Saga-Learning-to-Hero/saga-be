package com.saga.be.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.AccountDisabledEvent;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminUserCommandServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private AdminUserQueryService queries;
	@Mock
	private AuditService audit;
	@Mock
	private ApplicationEventPublisher events;

	private AdminUserCommandService service;

	@BeforeEach
	void setUp() {
		service = new AdminUserCommandService(users, queries, audit, events);
	}

	@Test
	void studentActiveToInactiveWritesAuditAndLocksRow() {
		UserAccount actor = account(UUID.randomUUID(), AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount target = account(UUID.randomUUID(), AccountRole.STUDENT, AccountStatus.ACTIVE);
		AdminUserResponse response = response(target.getId(), "STUDENT", "INACTIVE");
		when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
		when(users.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(queries.get(target.getId())).thenReturn(response);

		AdminUserResponse result = service.updateStatus(actor.getId(), target.getId(), "INACTIVE", auditRequest());

		assertEquals("INACTIVE", result.accountStatus());
		assertEquals(AccountStatus.INACTIVE, target.getAccountStatus());
		verify(users).findByIdForUpdate(target.getId());
		verify(users).save(target);
		verify(events).publishEvent(any(AccountDisabledEvent.class));
		verify(audit)
				.record(
						eq(actor),
						isNull(),
						isNull(),
						eq(AdminUserCommandService.USER_STATUS_CHANGED),
						eq(AdminUserCommandService.ENTITY_TYPE),
						eq(target.getId()),
						eq(Map.of("accountStatus", "ACTIVE")),
						eq(Map.of("accountStatus", "INACTIVE")),
						eq(Map.of("role", "STUDENT")),
						eq(AuditSource.API),
						eq("req-1"),
						eq("127.0.0.1"),
						eq("JUnit"));
	}

	@Test
	void lecturerInactiveToActiveWritesAudit() {
		UserAccount actor = account(UUID.randomUUID(), AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount target = account(UUID.randomUUID(), AccountRole.LECTURER, AccountStatus.INACTIVE);
		when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
		when(users.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(queries.get(target.getId())).thenReturn(response(target.getId(), "LECTURER", "ACTIVE"));

		AdminUserResponse result = service.updateStatus(actor.getId(), target.getId(), "ACTIVE", auditRequest());

		assertEquals("ACTIVE", result.accountStatus());
		verify(events, never()).publishEvent(any(AccountDisabledEvent.class));
		verify(audit)
				.record(
						eq(actor),
						isNull(),
						isNull(),
						eq(AdminUserCommandService.USER_STATUS_CHANGED),
						eq("user_account"),
						eq(target.getId()),
						eq(Map.of("accountStatus", "INACTIVE")),
						eq(Map.of("accountStatus", "ACTIVE")),
						eq(Map.of("role", "LECTURER")),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void noOpDoesNotWriteAudit() {
		UserAccount actor = account(UUID.randomUUID(), AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount target = account(UUID.randomUUID(), AccountRole.STUDENT, AccountStatus.INACTIVE);
		when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
		when(users.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(queries.get(target.getId())).thenReturn(response(target.getId(), "STUDENT", "INACTIVE"));

		AdminUserResponse result = service.updateStatus(actor.getId(), target.getId(), "inactive", auditRequest());

		assertEquals("INACTIVE", result.accountStatus());
		verify(users, never()).save(any());
		verifyNoInteractions(audit);
		verify(events, never()).publishEvent(any(AccountDisabledEvent.class));
	}

	@Test
	void missingAndAdminTargetsShareNotFound() {
		UserAccount actor = account(UUID.randomUUID(), AccountRole.ADMIN, AccountStatus.ACTIVE);
		UUID missing = UUID.randomUUID();
		UserAccount adminTarget = account(UUID.randomUUID(), AccountRole.ADMIN, AccountStatus.ACTIVE);
		when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
		when(users.findByIdForUpdate(missing)).thenReturn(Optional.empty());
		when(users.findByIdForUpdate(adminTarget.getId())).thenReturn(Optional.of(adminTarget));

		AcademicException missingEx = assertThrows(
				AcademicException.class,
				() -> service.updateStatus(actor.getId(), missing, "INACTIVE", auditRequest()));
		AcademicException adminEx = assertThrows(
				AcademicException.class,
				() -> service.updateStatus(actor.getId(), adminTarget.getId(), "INACTIVE", auditRequest()));
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, missingEx.getCode());
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, adminEx.getCode());
		assertEquals(HttpStatus.NOT_FOUND, missingEx.getStatus());
		assertEquals(missingEx.getMessage(), adminEx.getMessage());
		verify(users, never()).save(any());
		verifyNoInteractions(audit);
		verify(events, never()).publishEvent(any(AccountDisabledEvent.class));
	}

	@Test
	void rejectsSuspendedPendingAndUnknown() {
		assertInvalid("SUSPENDED");
		assertInvalid("PENDING");
		assertInvalid("LOCKED");
		assertInvalid(" ");
		verifyNoInteractions(users, queries, audit, events);
	}

	private void assertInvalid(String status) {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.updateStatus(UUID.randomUUID(), UUID.randomUUID(), status, auditRequest()));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, ex.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
	}

	private static AuditRequest auditRequest() {
		return new AuditRequest("req-1", "127.0.0.1", "JUnit");
	}

	private static UserAccount account(UUID id, AccountRole role, AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail(id + "@fpt.edu.vn");
		account.setAccountRole(role);
		account.setAccountStatus(status);
		account.setPasswordHash("hash");
		return account;
	}

	private static AdminUserResponse response(UUID id, String role, String status) {
		return new AdminUserResponse(
				id, "user@fpt.edu.vn", "user", "User", null, role, status, "SE123456", null, LocalDateTime.of(2026, 1, 1, 0, 0));
	}
}
