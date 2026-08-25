package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.AuthProperties;
import com.saga.be.dto.auth.RegisterRequest;
import com.saga.be.dto.auth.RegisterResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class StudentRegistrationServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private StudentProfileRepository students;

	private PasswordEncoder passwordEncoder;
	private StudentRegistrationService registration;
	private LocalAuthService localAuth;

	@BeforeEach
	void setUp() {
		AuthProperties properties = new AuthProperties();
		passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
		registration = new StudentRegistrationService(
				users,
				students,
				passwordEncoder,
				new PasswordPolicy(properties),
				new InstitutionalEmailPolicy(properties));
		localAuth = new LocalAuthService(users, passwordEncoder, new AccountStatusGuard());
	}

	@Test
	void personalEmailCreatesStudentWithArgon2HashAndProfile() {
		when(users.existsByEmail("student@gmail.com")).thenReturn(false);
		when(students.existsByStudentCode("SE123456")).thenReturn(false);
		when(users.save(any())).thenAnswer(invocation -> {
			UserAccount account = invocation.getArgument(0);
			account.setId(UUID.randomUUID());
			return account;
		});
		when(students.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		RegisterResponse response = registration.register(request("student@gmail.com", "SE123456"));

		assertTrue(response.registered());
		assertEquals(AccountRole.STUDENT.name(), response.user().role());
		assertEquals("student@gmail.com", response.user().email());
		ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(accountCaptor.capture());
		UserAccount saved = accountCaptor.getValue();
		assertEquals(AccountRole.STUDENT, saved.getAccountRole());
		assertTrue(saved.getPasswordHash().startsWith("$argon2id$"));
		assertTrue(saved.getPasswordHash().contains("m=19456"));
		assertTrue(passwordEncoder.matches("example-password", saved.getPasswordHash()));
		assertFalse(saved.getPasswordHash().contains("example-password"));
		assertEquals(null, saved.getUsername());
		assertEquals(null, saved.getGoogleSubject());
		ArgumentCaptor<StudentProfile> profileCaptor = ArgumentCaptor.forClass(StudentProfile.class);
		verify(students).save(profileCaptor.capture());
		assertEquals("SE123456", profileCaptor.getValue().getStudentCode());
		assertNotEquals("student", profileCaptor.getValue().getStudentCode());
		assertNotEquals("student@gmail.com", profileCaptor.getValue().getStudentCode());
	}

	@Test
	void registrationCannotCreateLecturerOrAdminAndHasNoAcademicMembershipDependencies() {
		java.lang.reflect.Constructor<?> ctor = StudentRegistrationService.class.getDeclaredConstructors()[0];
		Set<Class<?>> types = Arrays.stream(ctor.getParameterTypes()).collect(Collectors.toSet());
		assertFalse(types.contains(CourseEnrollmentRepository.class));
		assertFalse(types.contains(TeamRepository.class));
		assertFalse(types.contains(LecturerProfileRepository.class));
		assertEquals(AccountRole.STUDENT, AccountRole.valueOf("STUDENT"));
	}

	@Test
	void requestRecordCannotCarryARole() {
		Set<String> names =
				Arrays.stream(RegisterRequest.class.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
		assertFalse(names.contains("role"));
		assertFalse(names.contains("accountRole"));
		assertFalse(names.contains("authorities"));
	}

	@Test
	void institutionalStudentEmailMustUseGoogle() {
		AuthException ex = assertThrows(
				AuthException.class, () -> registration.register(request("anvse170102@fpt.edu.vn", "SE170102")));
		assertEquals(AuthErrorCode.INSTITUTIONAL_EMAIL_USE_GOOGLE, ex.getCode());
		verify(users, never()).save(any());
	}

	@Test
	void institutionalLecturerEmailMustUseGoogle() {
		AuthException ex =
				assertThrows(AuthException.class, () -> registration.register(request("antv12@fe.edu.vn", "SE000001")));
		assertEquals(AuthErrorCode.INSTITUTIONAL_EMAIL_USE_GOOGLE, ex.getCode());
		verify(users, never()).save(any());
	}

	@Test
	void duplicateNormalizedEmailIsRejected() {
		when(users.existsByEmail("student@gmail.com")).thenReturn(true);
		AuthException ex =
				assertThrows(AuthException.class, () -> registration.register(request("  Student@Gmail.com ", "SE123456")));
		assertEquals(AuthErrorCode.EMAIL_ALREADY_REGISTERED, ex.getCode());
	}

	@Test
	void duplicateStudentCodeIsRejected() {
		when(users.existsByEmail("other@gmail.com")).thenReturn(false);
		when(students.existsByStudentCode("SE123456")).thenReturn(true);
		AuthException ex =
				assertThrows(AuthException.class, () -> registration.register(request("other@gmail.com", "SE123456")));
		assertEquals(AuthErrorCode.STUDENT_CODE_ALREADY_EXISTS, ex.getCode());
	}

	@Test
	void localLoginAfterRegistrationUsesStoredHashAndStudentRole() {
		UUID id = UUID.randomUUID();
		when(users.existsByEmail("student@outlook.com")).thenReturn(false);
		when(students.existsByStudentCode("SE654321")).thenReturn(false);
		when(users.save(any())).thenAnswer(invocation -> {
			UserAccount account = invocation.getArgument(0);
			account.setId(id);
			return account;
		});
		when(students.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		RegisterResponse created = registration.register(request("student@outlook.com", "SE654321"));
		ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(saved.capture());
		UserAccount persisted = saved.getValue();
		persisted.setId(created.user().id());
		when(users.findByEmail("student@outlook.com")).thenReturn(Optional.of(persisted));

		Authentication authentication = localAuth.authenticate("student@outlook.com", "example-password");
		SagaUserPrincipal principal = (SagaUserPrincipal) authentication.getPrincipal();
		assertEquals(AccountRole.STUDENT, principal.getRole());
		assertFalse(principal.isPasswordSetupRequired());
	}

	private static RegisterRequest request(String email, String studentCode) {
		return new RegisterRequest(email, "Example Student", studentCode, "example-password", "example-password");
	}
}
