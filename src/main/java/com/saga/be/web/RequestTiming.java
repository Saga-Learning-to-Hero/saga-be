package com.saga.be.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestTiming {

	public static final String SERVICE_METHOD_ATTR = "saga.serviceMethod";
	public static final String SERVICE_DURATION_MS_ATTR = "saga.serviceDurationMs";

	private static final Logger log = LoggerFactory.getLogger(RequestTiming.class);
	private static final long SERVICE_LOG_THRESHOLD_MS = 200;

	private RequestTiming() {}

	public static <T> T record(String method, Supplier<T> action) {
		long started = System.nanoTime();
		try {
			return action.get();
		} finally {
			long durationMs = (System.nanoTime() - started) / 1_000_000L;
			if (durationMs >= SERVICE_LOG_THRESHOLD_MS) {
				log.info("academic service method={} durationMs={}", method, durationMs);
			}
			RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
			if (attributes instanceof ServletRequestAttributes servletAttributes) {
				HttpServletRequest request = servletAttributes.getRequest();
				request.setAttribute(SERVICE_METHOD_ATTR, method);
				request.setAttribute(SERVICE_DURATION_MS_ATTR, durationMs);
			}
		}
	}
}
