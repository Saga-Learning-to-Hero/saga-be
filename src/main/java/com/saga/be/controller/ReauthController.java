package com.saga.be.controller;

import com.saga.be.auth.StepUpAuthenticationService;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.auth.ReauthPasswordRequest;
import com.saga.be.dto.auth.ReauthResponse;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/auth")
public class ReauthController {

	private final StepUpAuthenticationService stepUp;
	private final IntegrationProperties properties;

	public ReauthController(
			UserAccountRepository users, PasswordEncoder passwordEncoder, IntegrationProperties properties) {
		this.properties = properties;
		this.stepUp = new StepUpAuthenticationService(
				users::findById, passwordEncoder, properties.getReauthWindow(), properties.getReauthMaxFailures());
	}

	@PostMapping("/reauth/password")
	public ReauthResponse password(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody ReauthPasswordRequest request,
			HttpSession session) {
		Instant at = stepUp.reauthPassword(principal.getUserId(), request.password());
		session.setAttribute(StepUpAuthenticationService.SESSION_ATTR, at);
		return new ReauthResponse(true, at.plus(properties.getReauthWindow()));
	}

	@PostMapping("/reauth/webauthn")
	public ReauthResponse webauthn(@AuthenticationPrincipal SagaUserPrincipal principal, HttpSession session) {
		throw new IntegrationException(
				IntegrationErrorCode.WEBAUTHN_DISABLED,
				HttpStatus.BAD_REQUEST,
				properties.getWebauthn().isEnabled()
						? "Passkey assertion is not yet wired. Use password step-up."
						: "WebAuthn is disabled.");
	}
}
