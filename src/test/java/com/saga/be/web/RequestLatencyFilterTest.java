package com.saga.be.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLatencyFilterTest {

	@Test
	void safePathOmitsQueryString() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/courses");
		request.setQueryString("semesterId=secret-value");
		assertThat(RequestLatencyFilter.safePath(request)).isEqualTo("/api/admin/courses");
	}

	@Test
	void slowThresholdIsOneSecond() {
		assertThat(RequestLatencyFilter.SLOW_REQUEST_THRESHOLD_MS).isEqualTo(1000);
	}

	@Test
	void filterOrderIsBeforeSpringSessionDefault() {
		// SessionRepositoryFilter.DEFAULT_ORDER = Integer.MIN_VALUE + 50
		assertThat(OrderedValue.REQUEST_LATENCY_ORDER).isLessThan(OrderedValue.SESSION_REPOSITORY_ORDER);
	}

	@Test
	void deltaMsHandlesMissingAttrs() {
		assertThat(RequestLatencyFilter.deltaMs(null, 10L)).isNull();
		assertThat(RequestLatencyFilter.deltaMs(1L, 1_000_000L)).isEqualTo(0L);
		assertThat(RequestLatencyFilter.deltaMs(0L, 5_000_000L)).isEqualTo(5L);
		assertThat(RequestLatencyFilter.deltaMsFromStart(0L, 5_000_000L)).isEqualTo(5L);
		assertThat(RequestLatencyFilter.deltaMsToEnd(0L, 5_000_000L)).isEqualTo(5L);
	}

	@Test
	void constructsWithHikariSnapshot() {
		assertThat(new RequestLatencyFilter(mock(HikariRequestSnapshot.class))).isNotNull();
	}

	@Test
	void slowLogIncludesWorkloadClassAndNormalizedRouteNotUuid() {
		Logger logger = (Logger) LoggerFactory.getLogger(RequestLatencyFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			java.util.UUID projectId = java.util.UUID.fromString("5b99c0a0-1111-2222-3333-444444444444");
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/" + projectId + "/tasks");
			request.setAttribute(RequestPhaseAttrs.WORKLOAD_CLASS, "INTERACTIVE_NORMAL");
			request.setAttribute(RequestPhaseAttrs.ROUTE_PATTERN, "/api/projects/{projectId}/tasks");
			request.setAttribute(RequestPhaseAttrs.HIKARI_ACTIVE, 3);
			request.setAttribute(RequestPhaseAttrs.HIKARI_IDLE, 0);
			request.setAttribute(RequestPhaseAttrs.HIKARI_PENDING, 2);
			request.setAttribute(RequestTiming.SERVICE_DURATION_MS_ATTR, 1500L);
			MockHttpServletResponse response = new MockHttpServletResponse();
			response.setStatus(200);
			new RequestLatencyFilter(mock(HikariRequestSnapshot.class))
					.logSlow(request, response, 0L, 1_500_000_000L, 1500L);
			assertThat(appender.list).isNotEmpty();
			String message = appender.list.get(appender.list.size() - 1).getFormattedMessage();
			assertThat(message).contains("workloadClass=INTERACTIVE_NORMAL");
			assertThat(message).contains("routePattern=/api/projects/{projectId}/tasks");
			assertThat(message).contains("method=GET");
			assertThat(message).contains("status=200");
			assertThat(message).contains("durationMs=1500");
			assertThat(message).contains("preServiceMs=");
			assertThat(message).contains("serviceDurationMs=1500");
			assertThat(message).contains("hikariActive=3");
			assertThat(message).contains("hikariIdle=0");
			assertThat(message).contains("hikariPending=2");
			assertThat(message).doesNotContain("userId=");
			assertThat(message).doesNotContain("projectId=");
			assertThat(message).doesNotContain("courseId=");
			assertThat(message).doesNotContain("teamId=");
			assertThat(message).doesNotContain("notificationId=");
			assertThat(message).doesNotContain("routePattern=/api/projects/" + projectId);
		} finally {
			logger.detachAppender(appender);
		}
	}

	/** Mirrors documented servlet filter order constants used in diagnosis. */
	static final class OrderedValue {
		static final int REQUEST_LATENCY_ORDER = Integer.MIN_VALUE + 20;
		static final int SESSION_REPOSITORY_ORDER = Integer.MIN_VALUE + 50;
	}
}
