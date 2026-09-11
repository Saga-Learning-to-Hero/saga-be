package com.saga.be.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Rotates the session id (if one exists) and persists a fresh {@link SecurityContext} so the
 * server session cookie reflects the given authentication. Used after login, first password
 * setup, and self-profile update — any time the cached {@link SagaUserPrincipal} snapshot in the
 * session must be replaced rather than left stale for the rest of the session lifetime.
 */
@Component
public class SessionEstablisher {

	private final SecurityContextRepository securityContextRepository;

	public SessionEstablisher(SecurityContextRepository securityContextRepository) {
		this.securityContextRepository = securityContextRepository;
	}

	public void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
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
