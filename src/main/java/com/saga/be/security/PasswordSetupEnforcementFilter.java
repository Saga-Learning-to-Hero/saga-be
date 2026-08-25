package com.saga.be.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public class PasswordSetupEnforcementFilter extends OncePerRequestFilter {

	private static final RequestMatcher ALLOWED = new OrRequestMatcher(
			PathPatternRequestMatcher.pathPattern(HttpMethod.OPTIONS, "/**"),
			PathPatternRequestMatcher.pathPattern("/"),
			PathPatternRequestMatcher.pathPattern("/index.html"),
			PathPatternRequestMatcher.pathPattern("/swagger-ui.html"),
			PathPatternRequestMatcher.pathPattern("/swagger-ui/**"),
			PathPatternRequestMatcher.pathPattern("/v3/api-docs"),
			PathPatternRequestMatcher.pathPattern("/v3/api-docs.yaml"),
			PathPatternRequestMatcher.pathPattern("/v3/api-docs/**"),
			PathPatternRequestMatcher.pathPattern("/api/auth/me"),
			PathPatternRequestMatcher.pathPattern("/api/auth/csrf"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/register"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/password/setup"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout"),
			PathPatternRequestMatcher.pathPattern("/oauth2/**"),
			PathPatternRequestMatcher.pathPattern("/login/oauth2/**"));

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null
				&& authentication.getPrincipal() instanceof SagaUserPrincipal principal
				&& principal.isPasswordSetupRequired()
				&& !ALLOWED.matches(request)) {
			PasswordSetupAuthorizationManager.writePasswordSetupRequired(response);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
