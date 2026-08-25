package com.saga.be.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

final class PasswordSetupAuthorizationManager {

	private PasswordSetupAuthorizationManager() {}

	static void writePasswordSetupRequired(HttpServletResponse response) throws IOException {
		response.setStatus(403);
		response.setCharacterEncoding("UTF-8");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"code\":\"PASSWORD_SETUP_REQUIRED\",\"message\":\"Password setup is required.\"}");
	}
}
