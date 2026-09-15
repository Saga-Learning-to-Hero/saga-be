package com.saga.be.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AdminUserQueryRow;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminUserQueryServiceTest {

	@Mock
	private UserAccountRepository users;

	private AdminUserQueryService service;

	@BeforeEach
	void setUp() {
		service = new AdminUserQueryService(users);
	}

	@Test
	void listDefaultsPageAndSizeAndOmitsSecrets() {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(users.searchAdminUsers(isNull(), isNull(), isNull(), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(row(id, "Ada", "ada@fpt.edu.vn")), PageRequest.of(0, 50), 1));
		AdminUserPageResponse page = service.list(null, null, null, null, null);
		assertEquals(0, page.page());
		assertEquals(50, page.size());
		assertEquals(1, page.total());
		assertEquals(id, page.items().getFirst().id());
		assertEquals("STUDENT", page.items().getFirst().role());
		assertEquals("ACTIVE", page.items().getFirst().accountStatus());
		assertEquals("SE123456", page.items().getFirst().studentCode());
		assertNull(page.items().getFirst().lecturerProfileId());
	}

	@Test
	void listParsesRoleStatusAndSearch() {
		when(users.searchAdminUsers(
						eq(AccountRole.LECTURER),
						eq(AccountStatus.INACTIVE),
						eq("%lan%"),
						eq(PageRequest.of(1, 20))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 0));
		AdminUserPageResponse page = service.list(" Lan ", "lecturer", "inactive", 1, 20);
		assertEquals(1, page.page());
		assertEquals(20, page.size());
		verify(users).searchAdminUsers(AccountRole.LECTURER, AccountStatus.INACTIVE, "%lan%", PageRequest.of(1, 20));
	}

	@Test
	void likePatternEscapesWildcards() {
		assertEquals("%a\\%b\\_c%", AdminUserQueryService.likePattern("A%b_c"));
		assertNull(AdminUserQueryService.likePattern("  "));
	}

	@Test
	void listRejectsInvalidPageSizeAndEnums() {
		AcademicException pageEx = assertThrows(AcademicException.class, () -> service.list(null, null, null, -1, 50));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, pageEx.getCode());
		AcademicException sizeEx = assertThrows(AcademicException.class, () -> service.list(null, null, null, 0, 201));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, sizeEx.getCode());
		AcademicException roleEx = assertThrows(AcademicException.class, () -> service.list(null, "OWNER", null, 0, 50));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, roleEx.getCode());
		AcademicException statusEx = assertThrows(AcademicException.class, () -> service.list(null, null, "LOCKED", 0, 50));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, statusEx.getCode());
		AcademicException adminRole = assertThrows(AcademicException.class, () -> service.list(null, "ADMIN", null, 0, 50));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, adminRole.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, adminRole.getStatus());
	}

	@Test
	void getReturnsSame404ForMissingAndHiddenAdmin() {
		UUID missing = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		when(users.findAdminUserById(missing)).thenReturn(Optional.empty());
		when(users.findAdminUserById(adminId)).thenReturn(Optional.empty());
		AcademicException missingEx = assertThrows(AcademicException.class, () -> service.get(missing));
		AcademicException adminEx = assertThrows(AcademicException.class, () -> service.get(adminId));
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, missingEx.getCode());
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, adminEx.getCode());
		assertEquals(HttpStatus.NOT_FOUND, missingEx.getStatus());
		assertEquals(missingEx.getMessage(), adminEx.getMessage());
	}

	@Test
	void listUsesUnsortedPageRequestSoJpqlOrderWins() {
		when(users.searchAdminUsers(isNull(), isNull(), isNull(), org.mockito.ArgumentMatchers.any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
		service.list(null, null, null, 0, 50);
		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(users).searchAdminUsers(isNull(), isNull(), isNull(), captor.capture());
		assertTrue(captor.getValue().getSort().isUnsorted());
	}

	private static AdminUserQueryRow row(UUID id, String name, String email) {
		return new AdminUserQueryRow(
				id,
				email,
				"ada",
				name,
				null,
				AccountRole.STUDENT,
				AccountStatus.ACTIVE,
				"SE123456",
				null,
				LocalDateTime.of(2026, 1, 2, 3, 4));
	}
}
