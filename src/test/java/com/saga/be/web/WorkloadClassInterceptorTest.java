package com.saga.be.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class WorkloadClassInterceptorTest {

	@Test
	void storesEnumNameAndNormalizedSpringPattern() throws Exception {
		UUID projectId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/" + projectId + "/tasks");
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/projects/{projectId}/tasks");
		HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod("tasks"));
		assertThat(new WorkloadClassInterceptor().preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
		assertThat(request.getAttribute(RequestPhaseAttrs.WORKLOAD_CLASS))
				.isEqualTo(WorkloadClass.INTERACTIVE_NORMAL.name());
		assertThat(request.getAttribute(RequestPhaseAttrs.ROUTE_PATTERN)).isEqualTo("/api/projects/{projectId}/tasks");
		assertThat((String) request.getAttribute(RequestPhaseAttrs.ROUTE_PATTERN)).doesNotContain(projectId.toString());
	}

	@Test
	void methodOverrideAndUuidFallbackNormalization() throws Exception {
		UUID id = UUID.fromString("5b99c0a0-aaaa-bbbb-cccc-ddddeeeeffff");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/" + id + "/tasks");
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/projects/" + id + "/tasks");
		HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod("unread"));
		new WorkloadClassInterceptor().preHandle(request, new MockHttpServletResponse(), handler);
		assertThat(request.getAttribute(RequestPhaseAttrs.WORKLOAD_CLASS))
				.isEqualTo(WorkloadClass.INTERACTIVE_LIGHT.name());
		assertThat(request.getAttribute(RequestPhaseAttrs.ROUTE_PATTERN)).isEqualTo("/api/projects/{id}/tasks");
	}

	@Test
	void missingPatternDoesNotUseRawUuidPath() throws Exception {
		UUID id = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/" + id + "/tasks");
		HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod("tasks"));
		new WorkloadClassInterceptor().preHandle(request, new MockHttpServletResponse(), handler);
		assertThat(request.getAttribute(RequestPhaseAttrs.ROUTE_PATTERN)).isEqualTo("unmapped");
		assertThat((String) request.getAttribute(RequestPhaseAttrs.ROUTE_PATTERN)).doesNotContain(id.toString());
	}

	static class SampleController {
		public void tasks() {}

		@Workload(WorkloadClass.INTERACTIVE_LIGHT)
		public void unread() {}
	}
}
