package com.saga.be.auth;

import com.saga.be.dto.auth.AuthenticatedUserDto;
import com.saga.be.dto.auth.AuthMeResponse;
import com.saga.be.security.SagaUserPrincipal;

public final class AuthResponses {

	private AuthResponses() {}

	public static AuthMeResponse fromPrincipal(SagaUserPrincipal principal) {
		if (principal == null) {
			return new AuthMeResponse(false, false, null);
		}
		return new AuthMeResponse(
				true,
				principal.isPasswordSetupRequired(),
				new AuthenticatedUserDto(
						principal.getUserId(),
						principal.getEmail(),
						principal.getUsername(),
						principal.getFullName(),
						principal.getAvatarUrl(),
						principal.getRole().name()));
	}

	public static AuthMeResponse anonymous() {
		return new AuthMeResponse(false, false, null);
	}
}
