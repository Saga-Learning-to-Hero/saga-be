package com.saga.be.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Marks controller enter/exit nanos for phase reconstruction (no sensitive data). */
@Component
public class RequestPhaseInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getAttribute(RequestPhaseAttrs.CONTROLLER_START_NANOS) == null) {
			request.setAttribute(RequestPhaseAttrs.CONTROLLER_START_NANOS, System.nanoTime());
		}
		return true;
	}

	@Override
	public void afterCompletion(
			HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		request.setAttribute(RequestPhaseAttrs.CONTROLLER_END_NANOS, System.nanoTime());
	}
}
