package com.saga.be.auth;

import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.roster.InvitationClaimService;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class GoogleAccountService {

	private static final Logger log = LoggerFactory.getLogger(GoogleAccountService.class);

	private final UserAccountRepository users;
	private final StudentProfileRepository students;
	private final LecturerProfileRepository lecturers;
	private final GoogleRoleResolver roleResolver;
	private final AccountStatusGuard statusGuard;
	private final InvitationClaimService invitationClaims;

	public GoogleAccountService(
			UserAccountRepository users,
			StudentProfileRepository students,
			LecturerProfileRepository lecturers,
			GoogleRoleResolver roleResolver,
			AccountStatusGuard statusGuard) {
		this(users, students, lecturers, roleResolver, statusGuard, null);
	}

	@Autowired
	public GoogleAccountService(
			UserAccountRepository users,
			StudentProfileRepository students,
			LecturerProfileRepository lecturers,
			GoogleRoleResolver roleResolver,
			AccountStatusGuard statusGuard,
			InvitationClaimService invitationClaims) {
		this.users = users;
		this.students = students;
		this.lecturers = lecturers;
		this.roleResolver = roleResolver;
		this.statusGuard = statusGuard;
		this.invitationClaims = invitationClaims;
	}

	@Transactional
	public UserAccount authenticateOrProvision(GoogleOidcIdentity identity, Set<String> allowedHostedDomains) {
		validateIdentity(identity, allowedHostedDomains);
		String sub = identity.subject();
		String email = identity.email().trim().toLowerCase(Locale.ROOT);

		UserAccount account = users.findByGoogleSubject(sub)
				.map(this::useExisting)
				.orElseGet(() -> users.findByEmail(email)
						.map(existing -> linkSubject(existing, sub, allowedHostedDomains, identity))
						.orElseGet(() -> createNew(identity, email, sub, allowedHostedDomains)));
		if (account.getAccountRole() == AccountRole.STUDENT && invitationClaims != null) {
			invitationClaims.claimQuietly(account);
		}
		return account;
	}

	private UserAccount useExisting(UserAccount account) {
		statusGuard.requireActive(account);
		log.info("auth method=GOOGLE result=success userId={} role={}", account.getId(), account.getAccountRole());
		return account;
	}

	private UserAccount linkSubject(
			UserAccount existing, String sub, Set<String> allowedHostedDomains, GoogleOidcIdentity identity) {
		statusGuard.requireActive(existing);
		if (!roleResolver.isInstitutionalGoogle(
				existing.getEmail().toLowerCase(Locale.ROOT), identity.hostedDomain(), allowedHostedDomains)) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_DOMAIN_NOT_ALLOWED, HttpStatus.FORBIDDEN, "Google account is not allowed.");
		}
		if (StringUtils.hasText(existing.getGoogleSubject()) && !existing.getGoogleSubject().equals(sub)) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_IDENTITY_CONFLICT,
					HttpStatus.CONFLICT,
					"Google identity is already linked to a different account.");
		}
		if (!StringUtils.hasText(existing.getGoogleSubject())) {
			existing.setGoogleSubject(sub);
		}
		applyConservativeProfile(existing, identity);
		log.info("auth method=GOOGLE result=linked userId={} role={}", existing.getId(), existing.getAccountRole());
		return users.save(existing);
	}

	private UserAccount createNew(GoogleOidcIdentity identity, String email, String sub, Set<String> allowedHostedDomains) {
		GoogleRoleResolver.Outcome outcome =
				roleResolver.resolve(email, identity.emailVerified(), identity.hostedDomain(), allowedHostedDomains);
		AccountRole role = switch (outcome) {
			case STUDENT -> AccountRole.STUDENT;
			case LECTURER -> AccountRole.LECTURER;
			case REJECT_UNVERIFIED -> throw new AuthException(
					AuthErrorCode.GOOGLE_EMAIL_NOT_VERIFIED, HttpStatus.FORBIDDEN, "Google email is not verified.");
			case REJECT_DOMAIN -> throw new AuthException(
					AuthErrorCode.GOOGLE_DOMAIN_NOT_ALLOWED, HttpStatus.FORBIDDEN, "Google account is not allowed.");
			case REJECT_INELIGIBLE -> throw new AuthException(
					AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE,
					HttpStatus.FORBIDDEN,
					"Google account is not eligible for SAGA.");
		};
		try {
			UserAccount created = new UserAccount();
			created.setEmail(email);
			created.setGoogleSubject(sub);
			created.setFullName(identity.name());
			created.setAvatarUrl(identity.picture());
			created.setAccountRole(role);
			created.setAccountStatus(AccountStatus.ACTIVE);
			UserAccount saved = users.save(created);
			if (role == AccountRole.STUDENT) {
				StudentProfile profile = new StudentProfile();
				profile.setUserAccount(saved);
				if (GoogleRoleResolver.STUDENT_FPT_EMAIL.matcher(email).matches()) {
					profile.setStudentCode(email.substring(0, email.indexOf('@')));
				}
				students.save(profile);
			} else {
				LecturerProfile profile = new LecturerProfile();
				profile.setUserAccount(saved);
				lecturers.save(profile);
			}
			log.info("auth method=GOOGLE result=provisioned userId={} role={}", saved.getId(), role);
			return saved;
		} catch (DataIntegrityViolationException ex) {
			return users.findByGoogleSubject(sub)
					.or(() -> users.findByEmail(email))
					.map(this::useExisting)
					.orElseThrow(() -> ex);
		}
	}

	private void applyConservativeProfile(UserAccount existing, GoogleOidcIdentity identity) {
		if (!StringUtils.hasText(existing.getFullName()) && StringUtils.hasText(identity.name())) {
			existing.setFullName(identity.name());
		}
		if (!StringUtils.hasText(existing.getAvatarUrl()) && StringUtils.hasText(identity.picture())) {
			existing.setAvatarUrl(identity.picture());
		}
	}

	private void validateIdentity(GoogleOidcIdentity identity, Set<String> allowedHostedDomains) {
		if (identity == null || !StringUtils.hasText(identity.subject()) || !StringUtils.hasText(identity.email())) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE, HttpStatus.FORBIDDEN, "Google account is not eligible for SAGA.");
		}
		if (!identity.emailVerified()) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_EMAIL_NOT_VERIFIED, HttpStatus.FORBIDDEN, "Google email is not verified.");
		}
		Set<String> allowed = allowedHostedDomains == null ? Set.of() : new LinkedHashSet<>(allowedHostedDomains);
		if (!roleResolver.isInstitutionalGoogle(
				identity.email().trim().toLowerCase(Locale.ROOT), identity.hostedDomain(), allowed)) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_DOMAIN_NOT_ALLOWED, HttpStatus.FORBIDDEN, "Google account is not allowed.");
		}
	}

	public record GoogleOidcIdentity(
			String subject, String email, boolean emailVerified, String hostedDomain, String name, String picture) {}
}
