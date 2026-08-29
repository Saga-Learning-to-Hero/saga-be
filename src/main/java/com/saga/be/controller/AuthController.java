package com.saga.be.controller;

import com.saga.be.auth.AuthErrorCode;
import com.saga.be.auth.AuthResponses;
import com.saga.be.auth.LocalAuthService;
import com.saga.be.auth.PasswordSetupService;
import com.saga.be.auth.StudentRegistrationService;
import com.saga.be.config.OpenApiConfig;
import com.saga.be.dto.ApiErrorResponse;
import com.saga.be.dto.auth.AuthMeResponse;
import com.saga.be.dto.auth.CsrfTokenResponse;
import com.saga.be.dto.auth.LoginRequest;
import com.saga.be.dto.auth.PasswordSetupRequest;
import com.saga.be.dto.auth.RegisterRequest;
import com.saga.be.dto.auth.RegisterResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AuthException;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.roster.InvitationClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Session + CSRF Auth V1. No Bearer JWT.")
public class AuthController {

	private final LocalAuthService localAuthService;
	private final PasswordSetupService passwordSetupService;
	private final StudentRegistrationService studentRegistrationService;
	private final SecurityContextRepository securityContextRepository;
	private final ObjectProvider<InvitationClaimService> invitationClaims;

	public AuthController(
			LocalAuthService localAuthService,
			PasswordSetupService passwordSetupService,
			StudentRegistrationService studentRegistrationService,
			SecurityContextRepository securityContextRepository,
			ObjectProvider<InvitationClaimService> invitationClaims) {
		this.localAuthService = localAuthService;
		this.passwordSetupService = passwordSetupService;
		this.studentRegistrationService = studentRegistrationService;
		this.securityContextRepository = securityContextRepository;
		this.invitationClaims = invitationClaims;
	}

	@GetMapping("/csrf")
	@Operation(
			summary = "Issue CSRF token",
			description =
					"Returns the raw CSRF token and sets cookie `XSRF-TOKEN`. This endpoint has no request parameters or body. Send the same value as header `X-XSRF-TOKEN` on POST/PUT/PATCH/DELETE. Swagger UI copies the cookie automatically.",
			parameters = {})
	@ApiResponse(
			responseCode = "200",
			description = "CSRF token for this browser",
			content = @Content(schema = @Schema(implementation = CsrfTokenResponse.class)))
	public CsrfTokenResponse csrf(@Parameter(hidden = true) CsrfToken token) {
		return CsrfTokenResponse.from(token);
	}

	@GetMapping("/me")
	@Operation(
			summary = "Current session",
			description =
					"Returns the authenticated user when cookie `SAGA_SESSION` is valid. Anonymous callers receive authenticated=false. Role is loaded from MySQL.")
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "Current authentication state",
				content =
						@Content(
								mediaType = MediaType.APPLICATION_JSON_VALUE,
								schema = @Schema(implementation = AuthMeResponse.class),
								examples = {
									@ExampleObject(
											name = "authenticated",
											value =
													"""
													{"authenticated":true,"passwordSetupRequired":false,"user":{"id":"00000000-0000-0000-0000-000000000001","email":"admin@saga.local","username":"admin","fullName":"System Admin","avatarUrl":null,"role":"ADMIN"}}
													"""),
									@ExampleObject(
											name = "unauthenticated",
											value =
													"""
													{"authenticated":false,"passwordSetupRequired":false,"user":null}
													""")
								}))
	})
	public AuthMeResponse me(
			@Parameter(hidden = true) @AuthenticationPrincipal Object principal,
			@Parameter(hidden = true) Authentication authentication) {
		if (!(principal instanceof SagaUserPrincipal saga)
				|| authentication == null
				|| authentication instanceof AnonymousAuthenticationToken) {
			return AuthResponses.anonymous();
		}
		return AuthResponses.fromPrincipal(saga);
	}

	@PostMapping("/login")
	@Operation(
			summary = "Local username/email login",
			description =
					"""
					Public endpoint, CSRF-protected. On success the server sets HttpOnly cookie `SAGA_SESSION` \
					(server-side session in Valkey/Redis). The client must not send `role`. Identifier with `@` \
					is treated as email; otherwise username.

					Call `GET /api/auth/csrf` first. Missing/invalid CSRF returns 403 ACCESS_DENIED.
					""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Authenticated session established"),
		@ApiResponse(
				responseCode = "401",
				description = "Invalid identifier or password",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												name = "INVALID_CREDENTIALS",
												value = "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}"))),
		@ApiResponse(
				responseCode = "403",
				description = "CSRF rejected, account disabled, or password setup required on an existing session",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples = {
									@ExampleObject(
											name = "ACCESS_DENIED",
											value = "{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}"),
									@ExampleObject(
											name = "PASSWORD_SETUP_REQUIRED",
											value =
													"{\"code\":\"PASSWORD_SETUP_REQUIRED\",\"message\":\"Password setup is required.\"}")
								}))
	})
	public AuthMeResponse login(
			@Valid @RequestBody LoginRequest request,
			@Parameter(hidden = true) HttpServletRequest httpRequest,
			@Parameter(hidden = true) HttpServletResponse httpResponse) {
		Authentication authentication = localAuthService.authenticate(request.identifier(), request.password());
		establishSession(authentication, httpRequest, httpResponse);
		if (authentication.getPrincipal() instanceof SagaUserPrincipal principal) {
			InvitationClaimService claims = invitationClaims.getIfAvailable();
			if (claims != null) {
				UserAccount account = new UserAccount();
				account.setId(principal.getUserId());
				account.setEmail(principal.getEmail());
				account.setAccountRole(principal.getRole());
				claims.claimQuietly(account);
			}
			return AuthResponses.fromPrincipal(principal);
		}
		return AuthResponses.fromPrincipal((SagaUserPrincipal) authentication.getPrincipal());
	}

	@PostMapping("/register")
	@Operation(
			summary = "Public Student registration",
			description =
					"""
					Public, CSRF-protected. Always creates `STUDENT`. The client must not send `role`. \
					Institutional `@fpt.edu.vn` / `@fe.edu.vn` (and other configured Google hosted domains) \
					must use Google login instead.

					Does not create a session. Does not create Team membership. Matching PENDING course \
					invitations for this verified email + StudentCode are claimed automatically.
					""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			required = true,
			content =
					@Content(
							schema = @Schema(implementation = RegisterRequest.class),
							examples =
									@ExampleObject(
											name = "personalStudent",
											value =
													"""
													{"email":"student.personal@example.com","fullName":"Example Student","studentCode":"SE123456","password":"example-password","confirmPassword":"example-password"}
													""")))
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "STUDENT account created"),
		@ApiResponse(
				responseCode = "400",
				description = "Invalid data, password policy, or institutional email",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples = {
									@ExampleObject(
											name = "INSTITUTIONAL_EMAIL_USE_GOOGLE",
											value =
													"{\"code\":\"INSTITUTIONAL_EMAIL_USE_GOOGLE\",\"message\":\"Use Google login for institutional FPT/FE accounts.\"}"),
									@ExampleObject(
											name = "PASSWORD_POLICY_VIOLATION",
											value = "{\"code\":\"PASSWORD_POLICY_VIOLATION\",\"message\":\"Password does not meet policy.\"}")
								})),
		@ApiResponse(
				responseCode = "403",
				description = "CSRF rejected",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												name = "ACCESS_DENIED",
												value = "{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}"))),
		@ApiResponse(
				responseCode = "409",
				description = "Email or student code already registered",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												name = "EMAIL_ALREADY_REGISTERED",
												value = "{\"code\":\"EMAIL_ALREADY_REGISTERED\",\"message\":\"This email is already registered.\"}")))
	})
	public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(studentRegistrationService.register(request));
	}

	@PostMapping("/password/setup")
	@SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
	@Operation(
			summary = "Set first local password",
			description =
					"Requires an authenticated Google-linked STUDENT or LECTURER session with password_hash NULL. CSRF required. Does not overwrite an existing hash. ADMIN cannot use this endpoint.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Password stored; session no longer restricted"),
		@ApiResponse(
				responseCode = "401",
				description = "Not authenticated",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												value = "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}"))),
		@ApiResponse(
				responseCode = "403",
				description = "CSRF rejected or access denied",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												value = "{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}")))
	})
	public AuthMeResponse setupPassword(
			@Valid @RequestBody PasswordSetupRequest request,
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal,
			@Parameter(hidden = true) HttpServletRequest httpRequest,
			@Parameter(hidden = true) HttpServletResponse httpResponse) {
		if (principal == null) {
			throw new AuthException(
					AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed.");
		}
		Authentication authentication =
				passwordSetupService.setup(principal.getUserId(), request.newPassword(), request.confirmPassword());
		establishSession(authentication, httpRequest, httpResponse);
		return AuthResponses.fromPrincipal((SagaUserPrincipal) authentication.getPrincipal());
	}

	@PostMapping("/logout")
	@Operation(
			summary = "End session",
			description = "Invalidates the server session and clears cookies `SAGA_SESSION` and `XSRF-TOKEN`. CSRF required.")
	@ApiResponse(responseCode = "204", description = "Session invalidated")
	@ApiResponse(
			responseCode = "403",
			description = "CSRF rejected",
			content =
					@Content(
							schema = @Schema(implementation = ApiErrorResponse.class),
							examples =
									@ExampleObject(value = "{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}")))
	public ResponseEntity<Void> logout(
			@Parameter(hidden = true) HttpServletRequest request,
			@Parameter(hidden = true) HttpServletResponse response,
			@Parameter(hidden = true) Authentication authentication) {
		SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
		logoutHandler.setInvalidateHttpSession(true);
		logoutHandler.setClearAuthentication(true);
		logoutHandler.logout(request, response, authentication);
		new CookieClearingLogoutHandler("SAGA_SESSION", "XSRF-TOKEN").logout(request, response, authentication);
		SecurityContextHolder.clearContext();
		return ResponseEntity.noContent().build();
	}

	private void establishSession(
			Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
		HttpSession existing = request.getSession(false);
		if (existing != null) {
			request.changeSessionId();
		} else {
			request.getSession(true);
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
	}
}
