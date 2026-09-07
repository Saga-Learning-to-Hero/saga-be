package com.saga.be.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLatencyFilter extends OncePerRequestFilter {

	static final long SLOW_REQUEST_THRESHOLD_MS = 1000;

	private static final Logger log = LoggerFactory.getLogger(RequestLatencyFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long started = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} finally {
			long durationMs = (System.nanoTime() - started) / 1_000_000L;
			if (durationMs >= SLOW_REQUEST_THRESHOLD_MS) {
				Object serviceMethod = request.getAttribute(RequestTiming.SERVICE_METHOD_ATTR);
				Object serviceDurationMs = request.getAttribute(RequestTiming.SERVICE_DURATION_MS_ATTR);
				if (serviceMethod instanceof String method && serviceDurationMs instanceof Long serviceMs) {
					log.info(
							"slow request method={} path={} status={} durationMs={} serviceMethod={} serviceDurationMs={}",
							request.getMethod(),
							safePath(request),
							response.getStatus(),
							durationMs,
							method,
							serviceMs);
				} else {
					log.info(
							"slow request method={} path={} status={} durationMs={}",
							request.getMethod(),
							safePath(request),
							response.getStatus(),
							durationMs);
				}
			}
		}
	}

	static String safePath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri == null || uri.isBlank() ? "/" : uri;
	}
}
