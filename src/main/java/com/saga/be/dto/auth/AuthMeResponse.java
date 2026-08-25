package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Current authentication state. Unauthenticated callers receive authenticated=false and user=null.")
public record AuthMeResponse(
		boolean authenticated, boolean passwordSetupRequired, AuthenticatedUserDto user) {}
