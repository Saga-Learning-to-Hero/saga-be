package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.auth.UpdateProfileRequest;
import com.saga.be.dto.auth.UserProfileResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

	@Mock
	private UserAccountRepository users;

	@Mock
	private StudentProfileRepository students;

	private UserProfileService service;

	@BeforeEach
	void setUp() {
		service = new UserProfileService(users, students);
	}

	@Test
	void getProfileIncludesStudentCodeForStudent() {
		UUID id = UUID.randomUUID();
		UserAccount account = student(id, "not-hashed");
		when(users.findById(id)).thenReturn(Optional.of(account));
		StudentProfile profile = new StudentProfile();
		profile.setStudentCode("SE123456");
		when(students.findByUserAccount_Id(id)).thenReturn(Optional.of(profile));

		UserProfileResponse response = service.getProfile(id);

		assertEquals(id, response.id());
		assertEquals("anvse170102@fpt.edu.vn", response.email());
		assertEquals("STUDENT", response.role());
		assertEquals("ACTIVE", response.accountStatus());
		assertEquals("SE123456", response.studentCode());
	}

	@Test
	void getProfileOmitsStudentCodeForLecturer() {
		UUID id = UUID.randomUUID();
		UserAccount account = lecturer(id);
		when(users.findById(id)).thenReturn(Optional.of(account));

		UserProfileResponse response = service.getProfile(id);

		assertNull(response.studentCode());
		verify(students, never()).findByUserAccount_Id(any());
	}

	@Test
	void updatingOnlyFullNameLeavesAvatarUrlUnchanged() {
		UUID id = UUID.randomUUID();
		UserAccount account = lecturer(id);
		account.setAvatarUrl("https://old.example.com/a.png");
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.updateProfile(id, new UpdateProfileRequest("New Name", null));

		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(captor.capture());
		assertEquals("New Name", captor.getValue().getFullName());
		assertEquals("https://old.example.com/a.png", captor.getValue().getAvatarUrl());
	}

	@Test
	void updatingBothFieldsAppliesBoth() {
		UUID id = UUID.randomUUID();
		UserAccount account = lecturer(id);
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.updateProfile(id, new UpdateProfileRequest("New Name", "https://example.com/new.png"));

		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(captor.capture());
		assertEquals("New Name", captor.getValue().getFullName());
		assertEquals("https://example.com/new.png", captor.getValue().getAvatarUrl());
	}

	@Test
	void blankAvatarUrlClearsIt() {
		UUID id = UUID.randomUUID();
		UserAccount account = lecturer(id);
		account.setAvatarUrl("https://old.example.com/a.png");
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.updateProfile(id, new UpdateProfileRequest(null, "   "));

		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(captor.capture());
		assertNull(captor.getValue().getAvatarUrl());
	}

	@Test
	void blankFullNameIsRejected() {
		UUID id = UUID.randomUUID();
		when(users.findById(id)).thenReturn(Optional.of(lecturer(id)));

		AuthException ex = assertThrows(
				AuthException.class, () -> service.updateProfile(id, new UpdateProfileRequest("   ", null)));
		assertEquals(AuthErrorCode.PROFILE_FULL_NAME_INVALID, ex.getCode());
		verify(users, never()).save(any());
	}

	@Test
	void nonHttpAvatarUrlIsRejected() {
		UUID id = UUID.randomUUID();
		when(users.findById(id)).thenReturn(Optional.of(lecturer(id)));

		AuthException ex = assertThrows(
				AuthException.class,
				() -> service.updateProfile(id, new UpdateProfileRequest(null, "javascript:alert(1)")));
		assertEquals(AuthErrorCode.PROFILE_AVATAR_URL_INVALID, ex.getCode());
		verify(users, never()).save(any());
	}

	@Test
	void updateNeverTouchesProtectedFields() {
		UUID id = UUID.randomUUID();
		UserAccount account = student(id, "$argon2id$hash");
		account.setUsername("original-username");
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.updateProfile(id, new UpdateProfileRequest("New Name", null));

		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(captor.capture());
		UserAccount saved = captor.getValue();
		assertEquals(id, saved.getId());
		assertEquals("anvse170102@fpt.edu.vn", saved.getEmail());
		assertEquals("original-username", saved.getUsername());
		assertEquals(AccountRole.STUDENT, saved.getAccountRole());
		assertEquals(AccountStatus.ACTIVE, saved.getAccountStatus());
		assertEquals("$argon2id$hash", saved.getPasswordHash());
	}

	@Test
	void updateReturnsFreshAuthenticationReflectingChange() {
		UUID id = UUID.randomUUID();
		UserAccount account = lecturer(id);
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Authentication authentication = service.updateProfile(id, new UpdateProfileRequest("New Name", null));

		SagaUserPrincipal principal = (SagaUserPrincipal) authentication.getPrincipal();
		assertEquals("New Name", principal.getFullName());
		assertEquals(id, principal.getUserId());
	}

	@Test
	void unknownUserRaisesUnauthorized() {
		UUID id = UUID.randomUUID();
		when(users.findById(id)).thenReturn(Optional.empty());

		AuthException ex = assertThrows(AuthException.class, () -> service.getProfile(id));
		assertEquals(AuthErrorCode.INVALID_CREDENTIALS, ex.getCode());
	}

	private static UserAccount student(UUID id, String passwordHash) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail("anvse170102@fpt.edu.vn");
		account.setUsername("anvse170102");
		account.setFullName("Original Name");
		account.setGoogleSubject("google-sub-student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash(passwordHash);
		return account;
	}

	private static UserAccount lecturer(UUID id) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail("antv12@fpt.edu.vn");
		account.setUsername("antv12");
		account.setFullName("Original Lecturer");
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}
