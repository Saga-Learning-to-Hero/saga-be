package com.saga.be.auth;

import com.saga.be.dto.auth.RegisterRequest;
import com.saga.be.dto.auth.RegisterResponse;
import com.saga.be.dto.auth.RegisterResponse.RegisteredUserDto;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.roster.InvitationClaimService;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class StudentRegistrationService {

	private static final Logger log = LoggerFactory.getLogger(StudentRegistrationService.class);

	private final UserAccountRepository users;
	private final StudentProfileRepository students;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final InstitutionalEmailPolicy institutionalEmails;
	private final InvitationClaimService invitationClaims;

	public StudentRegistrationService(
			UserAccountRepository users,
			StudentProfileRepository students,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			InstitutionalEmailPolicy institutionalEmails) {
		this(users, students, passwordEncoder, passwordPolicy, institutionalEmails, null);
	}

	@Autowired
	public StudentRegistrationService(
			UserAccountRepository users,
			StudentProfileRepository students,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			InstitutionalEmailPolicy institutionalEmails,
			InvitationClaimService invitationClaims) {
		this.users = users;
		this.students = students;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.institutionalEmails = institutionalEmails;
		this.invitationClaims = invitationClaims;
	}

	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		if (request == null) {
			throw invalid();
		}
		String email = normalizeEmail(request.email());
		String fullName = requireText(request.fullName(), 255);
		String studentCode = requireText(request.studentCode(), 64);
		passwordPolicy.validate(request.password(), request.confirmPassword());
		if (institutionalEmails.isInstitutionalEmail(email)) {
			throw new AuthException(
					AuthErrorCode.INSTITUTIONAL_EMAIL_USE_GOOGLE,
					HttpStatus.BAD_REQUEST,
					"Use Google login for institutional FPT/FE accounts.");
		}
		if (users.existsByEmail(email)) {
			throw new AuthException(
					AuthErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT, "This email is already registered.");
		}
		if (students.existsByStudentCode(studentCode)) {
			throw new AuthException(
					AuthErrorCode.STUDENT_CODE_ALREADY_EXISTS, HttpStatus.CONFLICT, "This student code is already registered.");
		}

		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setUsername(null);
		account.setGoogleSubject(null);
		account.setFullName(fullName);
		account.setPasswordHash(passwordEncoder.encode(request.password()));
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);

		try {
			UserAccount saved = users.save(account);
			StudentProfile profile = new StudentProfile();
			profile.setUserAccount(saved);
			profile.setStudentCode(studentCode);
			profile.setVersion(0L);
			students.save(profile);
			log.info("auth method=REGISTER result=created userId={} role=STUDENT", saved.getId());
			if (invitationClaims != null) {
				invitationClaims.claimQuietly(saved);
			}
			return new RegisterResponse(
					true, new RegisteredUserDto(saved.getId(), saved.getEmail(), saved.getFullName(), AccountRole.STUDENT.name()));
		} catch (DataIntegrityViolationException ex) {
			if (users.existsByEmail(email)) {
				throw new AuthException(
						AuthErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT, "This email is already registered.");
			}
			if (students.existsByStudentCode(studentCode)) {
				throw new AuthException(
						AuthErrorCode.STUDENT_CODE_ALREADY_EXISTS,
						HttpStatus.CONFLICT,
						"This student code is already registered.");
			}
			throw ex;
		}
	}

	private static String normalizeEmail(String email) {
		if (!StringUtils.hasText(email) || !email.contains("@")) {
			throw invalid();
		}
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		if (InstitutionalEmailPolicy.domainOf(normalized) == null) {
			throw invalid();
		}
		return normalized;
	}

	private static String requireText(String value, int maxLength) {
		if (!StringUtils.hasText(value)) {
			throw invalid();
		}
		String trimmed = value.trim();
		if (trimmed.length() > maxLength) {
			throw invalid();
		}
		return trimmed;
	}

	private static AuthException invalid() {
		return new AuthException(
				AuthErrorCode.INVALID_REGISTRATION_DATA, HttpStatus.BAD_REQUEST, "Registration data is invalid.");
	}
}
