package com.saga.be.web;

import static org.assertj.core.api.Assertions.assertThat;

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
}
