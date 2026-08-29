package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.AuthProperties;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LecturerBootstrapRunnerTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private LecturerProfileRepository lecturers;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private Environment environment;

	@Test
	void createsLecturerAccountAndProfileOnce() {
		AuthProperties properties = enabledProperties();
		when(environment.getActiveProfiles()).thenReturn(new String[] {"local"});
		when(users.findByEmail("lecturer1@fe.edu.vn")).thenReturn(Optional.empty());
		when(users.existsByUsername("lecturer1")).thenReturn(false);
		when(passwordEncoder.encode("local-only-secret")).thenReturn("$argon2id$hash");
		when(users.save(any(UserAccount.class))).thenAnswer(invocation -> {
			UserAccount account = invocation.getArgument(0);
			account.setId(UUID.randomUUID());
			return account;
		});
		when(lecturers.existsByUserAccount_Id(any())).thenReturn(false);

		runner(properties).run(null);

		verify(users).save(any(UserAccount.class));
		verify(lecturers).save(any(LecturerProfile.class));
		verify(passwordEncoder).encode("local-only-secret");
	}

	@Test
	void secondStartupDoesNotResetPassword() {
		AuthProperties properties = enabledProperties();
		UserAccount existing = lecturerAccount();
		when(users.findByEmail("lecturer1@fe.edu.vn")).thenReturn(Optional.of(existing));
		when(lecturers.existsByUserAccount_Id(existing.getId())).thenReturn(true);

		runner(properties).run(null);

		verify(users, never()).save(any());
		verify(lecturers, never()).save(any());
		verify(passwordEncoder, never()).encode(any());
	}

	@Test
	void existingLecturerGetsMissingProfileOnly() {
		AuthProperties properties = enabledProperties();
		UserAccount existing = lecturerAccount();
		when(users.findByEmail("lecturer1@fe.edu.vn")).thenReturn(Optional.of(existing));
		when(lecturers.existsByUserAccount_Id(existing.getId())).thenReturn(false);

		runner(properties).run(null);

		verify(users, never()).save(any());
		verify(passwordEncoder, never()).encode(any());
		verify(lecturers).save(any(LecturerProfile.class));
	}

	@Test
	void existingNonLecturerIsAConflict() {
		AuthProperties properties = enabledProperties();
		UserAccount student = lecturerAccount();
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findByEmail("lecturer1@fe.edu.vn")).thenReturn(Optional.of(student));

		assertThrows(IllegalStateException.class, () -> runner(properties).run(null));
		verify(users, never()).save(any());
		verify(lecturers, never()).save(any());
	}

	@Test
	void enabledWithoutPasswordFailsSafely() {
		AuthProperties properties = enabledProperties();
		properties.getBootstrapLecturer().setPassword("");
		when(users.findByEmail("lecturer1@fe.edu.vn")).thenReturn(Optional.empty());
		when(users.existsByUsername("lecturer1")).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> runner(properties).run(null));
	}

	@Test
	void nonLocalRejectsUnsafeDefaultPassword() {
		AuthProperties properties = enabledProperties();
		properties.getBootstrapLecturer().setPassword("lecturer123");
		when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
		when(users.findByEmail("lecturer1@fe.edu.vn")).thenReturn(Optional.empty());
		when(users.existsByUsername("lecturer1")).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> runner(properties).run(null));
	}

	@Test
	void disabledDoesNothing() {
		AuthProperties properties = new AuthProperties();
		runner(properties).run(null);
		verify(users, never()).findByEmail(any());
		verify(users, never()).save(any());
	}

	private LecturerBootstrapRunner runner(AuthProperties properties) {
		return new LecturerBootstrapRunner(properties, users, lecturers, passwordEncoder, environment);
	}

	private static AuthProperties enabledProperties() {
		AuthProperties properties = new AuthProperties();
		properties.getBootstrapLecturer().setEnabled(true);
		properties.getBootstrapLecturer().setUsername("lecturer1");
		properties.getBootstrapLecturer().setEmail("lecturer1@fe.edu.vn");
		properties.getBootstrapLecturer().setPassword("local-only-secret");
		return properties;
	}

	private static UserAccount lecturerAccount() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("lecturer1@fe.edu.vn");
		account.setUsername("lecturer1");
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}
