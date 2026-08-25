package com.saga.be.auth;

import com.saga.be.config.AuthProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@Profile("!test")
public class AdminBootstrapRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

	private final AuthProperties properties;
	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final Environment environment;

	public AdminBootstrapRunner(
			AuthProperties properties,
			UserAccountRepository users,
			PasswordEncoder passwordEncoder,
			Environment environment) {
		this.properties = properties;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.environment = environment;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		AuthProperties.BootstrapAdmin bootstrap = properties.getBootstrapAdmin();
		if (!bootstrap.isEnabled()) {
			return;
		}
		String username = bootstrap.getUsername();
		if (!StringUtils.hasText(username)) {
			throw new IllegalStateException("Bootstrap admin is enabled but username is not configured.");
		}
		if (users.existsByUsername(username)) {
			log.info("auth method=BOOTSTRAP result=exists role=ADMIN");
			return;
		}
		String password = bootstrap.getPassword();
		if (!StringUtils.hasText(password) || "<set-me>".equals(password)) {
			throw new IllegalStateException("Bootstrap admin is enabled but username/password is not configured.");
		}
		if (!isLocalProfile() && "saga123".equals(password)) {
			throw new IllegalStateException("Unsafe bootstrap admin password is not allowed outside local profile.");
		}
		UserAccount admin = new UserAccount();
		admin.setUsername(username);
		admin.setEmail(bootstrap.getEmail().trim().toLowerCase());
		admin.setAccountRole(AccountRole.ADMIN);
		admin.setAccountStatus(AccountStatus.ACTIVE);
		admin.setPasswordHash(passwordEncoder.encode(password));
		users.save(admin);
		log.info("auth method=BOOTSTRAP result=created role=ADMIN");
	}

	private boolean isLocalProfile() {
		String[] profiles = environment.getActiveProfiles();
		if (profiles == null) {
			return false;
		}
		for (String profile : profiles) {
			if ("local".equals(profile)) {
				return true;
			}
		}
		return false;
	}
}
