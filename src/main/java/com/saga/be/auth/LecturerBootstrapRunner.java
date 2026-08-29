package com.saga.be.auth;

import com.saga.be.config.AuthProperties;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.Locale;
import java.util.Optional;
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
public class LecturerBootstrapRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LecturerBootstrapRunner.class);

	private final AuthProperties properties;
	private final UserAccountRepository users;
	private final LecturerProfileRepository lecturers;
	private final PasswordEncoder passwordEncoder;
	private final Environment environment;

	public LecturerBootstrapRunner(
			AuthProperties properties,
			UserAccountRepository users,
			LecturerProfileRepository lecturers,
			PasswordEncoder passwordEncoder,
			Environment environment) {
		this.properties = properties;
		this.users = users;
		this.lecturers = lecturers;
		this.passwordEncoder = passwordEncoder;
		this.environment = environment;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		AuthProperties.BootstrapLecturer bootstrap = properties.getBootstrapLecturer();
		if (!bootstrap.isEnabled()) {
			return;
		}
		if (!StringUtils.hasText(bootstrap.getEmail())) {
			throw new IllegalStateException("Bootstrap lecturer is enabled but email is not configured.");
		}
		String email = bootstrap.getEmail().trim().toLowerCase(Locale.ROOT);
		if (!email.endsWith("@fe.edu.vn") && !email.endsWith("@fpt.edu.vn")) {
			throw new IllegalStateException("Bootstrap lecturer email must be an institutional lecturer domain.");
		}
		if (GoogleRoleResolver.STUDENT_FPT_EMAIL.matcher(email).matches()) {
			throw new IllegalStateException("Bootstrap lecturer email matches the student FPT pattern.");
		}
		String username = StringUtils.hasText(bootstrap.getUsername()) ? bootstrap.getUsername().trim() : null;
		Optional<UserAccount> existing = users.findByEmail(email);
		if (existing.isPresent()) {
			reuseExisting(existing.get(), username);
			return;
		}
		if (username != null && users.existsByUsername(username)) {
			throw new IllegalStateException("Bootstrap lecturer username is already used by a different account.");
		}
		String password = bootstrap.getPassword();
		if (!StringUtils.hasText(password) || "<set-me>".equals(password)) {
			throw new IllegalStateException("Bootstrap lecturer is enabled but password is not configured.");
		}
		if (!isLocalProfile() && "lecturer123".equals(password)) {
			throw new IllegalStateException("Unsafe bootstrap lecturer password is not allowed outside local profile.");
		}
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setUsername(username);
		account.setFullName(username != null ? username : email);
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash(passwordEncoder.encode(password));
		UserAccount saved = users.save(account);
		ensureLecturerProfile(saved);
		log.info("auth method=BOOTSTRAP result=created role=LECTURER");
	}

	private void reuseExisting(UserAccount existing, String username) {
		if (existing.getAccountRole() != AccountRole.LECTURER) {
			throw new IllegalStateException(
					"Bootstrap lecturer email already exists with a different account_role.");
		}
		if (username != null
				&& StringUtils.hasText(existing.getUsername())
				&& !username.equals(existing.getUsername())) {
			throw new IllegalStateException(
					"Bootstrap lecturer email exists but username does not match the requested identifier.");
		}
		ensureLecturerProfile(existing);
		log.info("auth method=BOOTSTRAP result=exists role=LECTURER");
	}

	private void ensureLecturerProfile(UserAccount account) {
		if (lecturers.existsByUserAccount_Id(account.getId())) {
			return;
		}
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		lecturers.save(profile);
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
