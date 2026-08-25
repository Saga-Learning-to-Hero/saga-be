package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.web.csrf.CsrfToken;

@Schema(description = "CSRF token issued for the current browser session. Copy `token` into header X-XSRF-TOKEN, or use cookie XSRF-TOKEN.")
public record CsrfTokenResponse(
		@Schema(description = "Form parameter name if submitting HTML forms.", example = "_csrf") String parameterName,
		@Schema(description = "Raw CSRF token. Same value as cookie XSRF-TOKEN.") String token,
		@Schema(description = "Header name to send on state-changing requests.", example = "X-XSRF-TOKEN")
				String headerName) {

	public static CsrfTokenResponse from(CsrfToken csrfToken) {
		return new CsrfTokenResponse(csrfToken.getParameterName(), csrfToken.getToken(), csrfToken.getHeaderName());
	}
}
