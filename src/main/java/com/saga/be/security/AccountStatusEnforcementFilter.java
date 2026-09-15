package com.saga.be.security;

import com.saga.be.auth.AccountStatusGuard;
import com.saga.be.auth.AuthErrorCode;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-checks {@code UserAccount.accountStatus} by primary key on authenticated API requests.
 * Existing Redis sessions are not enumerated or revoked; this is the request-time substitute.
 */
public class AccountStatusEnforcementFilter extends OncePerRequestFilter {

	static final RequestMatcher ALLOWED = new OrRequestMatcher(
			PathPatternRequestMatcher.pathPattern(HttpMethod.OPTIONS, "/**"),
			PathPatternRequestMatcher.pathPattern("/"),
			PathPatternRequestMatcher.pathPattern("/index.html"),
			PathPatternRequestMatcher.pathPattern("/privacy"),
			PathPatternRequestMatcher.pathPattern("/terms"),
			PathPatternRequestMatcher.pathPattern("/legal/**"),
			PathPatternRequestMatcher.pathPattern("/swagger-ui.html"),
			PathPatternRequestMatcher.pathPattern("/swagger-ui/**"),
			PathPatternRequestMatcher.pathPattern("/v3/api-docs"),
			PathPatternRequestMatcher.pathPattern("/v3/api-docs.yaml"),
			PathPatternRequestMatcher.pathPattern("/v3/api-docs/**"),
			PathPatternRequestMatcher.pathPattern("/error"),
			PathPatternRequestMatcher.pathPattern("/actuator/**"),
			PathPatternRequestMatcher.pathPattern("/oauth2/**"),
			PathPatternRequestMatcher.pathPattern("/login/oauth2/**"),
			PathPatternRequestMatcher.pathPattern("/api/auth/me"),
			PathPatternRequestMatcher.pathPattern("/api/auth/csrf"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/register"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/password/forgot"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/password/reset"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout"),
			PathPatternRequestMatcher.pathPattern("/api/webhooks/**"),
			PathPatternRequestMatcher.pathPattern("/api/integrations/github/oauth/callback"),
			PathPatternRequestMatcher.pathPattern("/api/integrations/jira/oauth/callback"),
			PathPatternRequestMatcher.pathPattern("/api/integrations/github/setup/callback"),
			PathPatternRequestMatcher.pathPattern("/api/integrations/jira/team/callback"),
			PathPatternRequestMatcher.pathPattern("/api/projects/*/integrations/github/setup/callback"));

	private final UserAccountRepository users;
	private final AccountStatusGuard statusGuard;

	public AccountStatusEnforcementFilter(UserAccountRepository users, AccountStatusGuard statusGuard) {
		this.users = users;
		this.statusGuard = statusGuard;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (users == null || statusGuard == null || ALLOWED.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof SagaUserPrincipal principal)) {
			filterChain.doFilter(request, response);
			return;
		}
		UserAccount account = users.findById(principal.getUserId()).orElse(null);
		if (account == null) {
			writeDisabled(response);
			return;
		}
		try {
			statusGuard.requireActive(account);
		} catch (AuthException ex) {
			writeDisabled(response);
			return;
		}
		filterChain.doFilter(request, response);
	}

	static void writeDisabled(HttpServletResponse response) throws IOException {
		response.setStatus(403);
		response.setCharacterEncoding("UTF-8");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter()
				.write("{\"code\":\"" + AuthErrorCode.ACCOUNT_DISABLED.name()
						+ "\",\"message\":\"Account is not available.\"}");
	}
}
