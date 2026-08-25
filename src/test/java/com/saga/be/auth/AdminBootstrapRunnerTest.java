package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.AuthProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private Environment environment;

	@Test
	void createsAdminOnce() {
		AuthProperties properties = new AuthProperties();
		properties.getBootstrapAdmin().setEnabled(true);
		properties.getBootstrapAdmin().setUsername("admin");
		properties.getBootstrapAdmin().setPassword("local-only-secret");
		when(environment.getActiveProfiles()).thenReturn(new String[] {"local"});
		when(users.existsByUsername("admin")).thenReturn(false);
		when(passwordEncoder.encode("local-only-secret")).thenReturn("$argon2id$hash");
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		new AdminBootstrapRunner(properties, users, passwordEncoder, environment).run(null);

		verify(users).save(any(UserAccount.class));
	}

	@Test
	void secondStartupDoesNotResetPassword() {
		AuthProperties properties = new AuthProperties();
		properties.getBootstrapAdmin().setEnabled(true);
		properties.getBootstrapAdmin().setUsername("admin");
		properties.getBootstrapAdmin().setPassword("local-only-secret");
		when(users.existsByUsername("admin")).thenReturn(true);

		new AdminBootstrapRunner(properties, users, passwordEncoder, environment).run(null);

		verify(users, never()).save(any());
		verify(passwordEncoder, never()).encode(any());
	}

	@Test
	void enabledWithoutPasswordFailsSafely() {
		AuthProperties properties = new AuthProperties();
		properties.getBootstrapAdmin().setEnabled(true);
		properties.getBootstrapAdmin().setPassword("");
		assertThrows(
				IllegalStateException.class,
				() -> new AdminBootstrapRunner(properties, users, passwordEncoder, environment).run(null));
	}

	@Test
	void nonLocalRejectsUnsafeDefaultPassword() {
		AuthProperties properties = new AuthProperties();
		properties.getBootstrapAdmin().setEnabled(true);
		properties.getBootstrapAdmin().setPassword("saga123");
		when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
		assertThrows(
				IllegalStateException.class,
				() -> new AdminBootstrapRunner(properties, users, passwordEncoder, environment).run(null));
	}
}
