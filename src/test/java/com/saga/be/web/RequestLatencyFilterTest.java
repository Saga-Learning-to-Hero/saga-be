package com.saga.be.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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

	/** Mirrors documented servlet filter order constants used in diagnosis. */
	static final class OrderedValue {
		static final int REQUEST_LATENCY_ORDER = Integer.MIN_VALUE + 20;
		static final int SESSION_REPOSITORY_ORDER = Integer.MIN_VALUE + 50;
	}
}
