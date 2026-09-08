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

/**
 * Outermost latency probe for authenticated/admin traffic diagnosis.
 *
 * <p>Order is {@code HIGHEST_PRECEDENCE + 20} ({@code Integer.MIN_VALUE + 20}), which is
 * earlier than Spring Session {@code SessionRepositoryFilter.DEFAULT_ORDER}
 * ({@code Integer.MIN_VALUE + 50}) and earlier than the Spring Security filter chain
 * (typically {@code -100}). Therefore {@code durationMs} includes session load/save,
 * security/auth, controller, service, and response serialization.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLatencyFilter extends OncePerRequestFilter {

	static final long SLOW_REQUEST_THRESHOLD_MS = 1000;

	private static final Logger log = LoggerFactory.getLogger(RequestLatencyFilter.class);

	private final HikariRequestSnapshot hikariSnapshot;

	public RequestLatencyFilter(HikariRequestSnapshot hikariSnapshot) {
		this.hikariSnapshot = hikariSnapshot;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long started = System.nanoTime();
		request.setAttribute(RequestPhaseAttrs.FILTER_START_NANOS, started);
		try {
			filterChain.doFilter(request, response);
		} finally {
			long ended = System.nanoTime();
			long durationMs = (ended - started) / 1_000_000L;
			if (durationMs >= SLOW_REQUEST_THRESHOLD_MS) {
				hikariSnapshot.capture(request);
				logSlow(request, response, started, ended, durationMs);
			}
		}
	}

	private void logSlow(
			HttpServletRequest request,
			HttpServletResponse response,
			long filterStartNanos,
			long filterEndNanos,
			long durationMs) {
		Object serviceMethod = request.getAttribute(RequestTiming.SERVICE_METHOD_ATTR);
		Object serviceDurationMs = request.getAttribute(RequestTiming.SERVICE_DURATION_MS_ATTR);
		Long preServiceMs = deltaMsFromStart(filterStartNanos, request.getAttribute(RequestPhaseAttrs.SERVICE_START_NANOS));
		if (preServiceMs == null) {
			preServiceMs = deltaMsFromStart(filterStartNanos, request.getAttribute(RequestPhaseAttrs.CONTROLLER_START_NANOS));
		}
		Long postServiceMs = deltaMsToEnd(request.getAttribute(RequestPhaseAttrs.SERVICE_END_NANOS), filterEndNanos);
		if (postServiceMs == null) {
			postServiceMs = deltaMsToEnd(request.getAttribute(RequestPhaseAttrs.CONTROLLER_END_NANOS), filterEndNanos);
		}
		Long controllerMs = deltaMs(
				request.getAttribute(RequestPhaseAttrs.CONTROLLER_START_NANOS),
				request.getAttribute(RequestPhaseAttrs.CONTROLLER_END_NANOS));
		Object sessionFindMs = request.getAttribute(RequestPhaseAttrs.SESSION_FIND_MS);
		Object sessionSaveMs = request.getAttribute(RequestPhaseAttrs.SESSION_SAVE_MS);
		Object sessionFindCount = request.getAttribute(RequestPhaseAttrs.SESSION_FIND_COUNT);
		Object sessionSaveCount = request.getAttribute(RequestPhaseAttrs.SESSION_SAVE_COUNT);
		Object hikariActive = request.getAttribute(RequestPhaseAttrs.HIKARI_ACTIVE);
		Object hikariIdle = request.getAttribute(RequestPhaseAttrs.HIKARI_IDLE);
		Object hikariPending = request.getAttribute(RequestPhaseAttrs.HIKARI_PENDING);

		log.info(
				"slow request method={} path={} status={} durationMs={} preServiceMs={} serviceMethod={} serviceDurationMs={} postServiceMs={} controllerMs={} sessionFindMs={} sessionFindCount={} sessionSaveMs={} sessionSaveCount={} hikariActive={} hikariIdle={} hikariPending={}",
				request.getMethod(),
				safePath(request),
				response.getStatus(),
				durationMs,
				preServiceMs,
				serviceMethod instanceof String method ? method : null,
				serviceDurationMs instanceof Long serviceMs ? serviceMs : null,
				postServiceMs,
				controllerMs,
				sessionFindMs instanceof Long findMs ? findMs : null,
				sessionFindCount instanceof Integer findCount ? findCount : null,
				sessionSaveMs instanceof Long saveMs ? saveMs : null,
				sessionSaveCount instanceof Integer saveCount ? saveCount : null,
				hikariActive instanceof Integer active ? active : null,
				hikariIdle instanceof Integer idle ? idle : null,
				hikariPending instanceof Integer pending ? pending : null);
	}

	static Long deltaMs(Object startNanos, Object endNanos) {
		if (!(startNanos instanceof Long start) || !(endNanos instanceof Long end)) {
			return null;
		}
		return Math.max(0L, (end - start) / 1_000_000L);
	}

	static Long deltaMsFromStart(long startNanos, Object endNanos) {
		if (!(endNanos instanceof Long end)) {
			return null;
		}
		return Math.max(0L, (end - startNanos) / 1_000_000L);
	}

	static Long deltaMsToEnd(Object startNanos, long endNanos) {
		if (!(startNanos instanceof Long start)) {
			return null;
		}
		return Math.max(0L, (endNanos - start) / 1_000_000L);
	}

	static String safePath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri == null || uri.isBlank() ? "/" : uri;
	}
}
