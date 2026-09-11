package com.saga.be.controller;

import com.saga.be.auth.AuthErrorCode;
import com.saga.be.auth.UserProfileService;
import com.saga.be.dto.ApiErrorResponse;
import com.saga.be.dto.auth.UpdateProfileRequest;
import com.saga.be.dto.auth.UserProfileResponse;
import com.saga.be.exception.AuthException;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.security.SessionEstablisher;
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
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/profile")
@SecurityRequirement(name = com.saga.be.config.OpenApiConfig.SESSION_COOKIE_SCHEME)
@Tag(name = "User Profile", description = "Self-service view/update of the authenticated user's own profile.")
public class UserProfileController {

	private final UserProfileService userProfileService;
	private final SessionEstablisher sessionEstablisher;

	public UserProfileController(UserProfileService userProfileService, SessionEstablisher sessionEstablisher) {
		this.userProfileService = userProfileService;
		this.sessionEstablisher = sessionEstablisher;
	}

	@GetMapping
	@Operation(
			summary = "Get my profile",
			description = "Returns the authenticated caller's own profile. studentCode is included only for STUDENT accounts.")
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "Current profile",
				content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
		@ApiResponse(
				responseCode = "401",
				description = "Not authenticated",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												value = "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}")))
	})
	public UserProfileResponse getMyProfile(@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal) {
		requirePrincipal(principal);
		return userProfileService.getProfile(principal.getUserId());
	}

	@PatchMapping
	@Operation(
			summary = "Update my profile",
			description =
					"""
					Partial update of the caller's own profile. Only fields present in the request body \
					are changed; omitted fields are left untouched. Editable fields: `fullName` \
					(non-blank if present) and `avatarUrl` (must be http(s):// if non-blank; an empty \
					string clears it). All other fields (id, email, username, role, accountStatus, \
					studentCode) are read-only and cannot be changed here. Returns the full updated \
					profile and refreshes the session so `/api/auth/me` reflects the change immediately.
					""")
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "Updated profile",
				content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
		@ApiResponse(
				responseCode = "400",
				description = "Invalid fullName or avatarUrl",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples = {
									@ExampleObject(
											name = "PROFILE_FULL_NAME_INVALID",
											value =
													"{\"code\":\"PROFILE_FULL_NAME_INVALID\",\"message\":\"Full name cannot be blank.\"}"),
									@ExampleObject(
											name = "PROFILE_AVATAR_URL_INVALID",
											value =
													"{\"code\":\"PROFILE_AVATAR_URL_INVALID\",\"message\":\"Avatar URL must start with http:// or https://.\"}")
								})),
		@ApiResponse(
				responseCode = "401",
				description = "Not authenticated",
				content =
						@Content(
								schema = @Schema(implementation = ApiErrorResponse.class),
								examples =
										@ExampleObject(
												value = "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}")))
	})
	public UserProfileResponse updateMyProfile(
			@Valid @RequestBody UpdateProfileRequest request,
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal,
			@Parameter(hidden = true) HttpServletRequest httpRequest,
			@Parameter(hidden = true) HttpServletResponse httpResponse) {
		requirePrincipal(principal);
		Authentication authentication = userProfileService.updateProfile(principal.getUserId(), request);
		sessionEstablisher.establish(authentication, httpRequest, httpResponse);
		SagaUserPrincipal refreshed = (SagaUserPrincipal) authentication.getPrincipal();
		return userProfileService.getProfile(refreshed.getUserId());
	}

	private static void requirePrincipal(SagaUserPrincipal principal) {
		if (principal == null) {
			throw new AuthException(
					AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed.");
		}
	}
}
