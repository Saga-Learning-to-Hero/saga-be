package com.saga.be.workload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class WorkloadClassifierTest {

	@Test
	void methodAnnotationOverridesTypeAnnotation() throws Exception {
		HandlerMethod method = handler(new TypedController(), "light");
		assertThat(WorkloadClassifier.resolve(method)).isEqualTo(WorkloadClass.INTERACTIVE_LIGHT);
	}

	@Test
	void typeAnnotationAppliesWhenMethodHasNone() throws Exception {
		HandlerMethod method = handler(new TypedController(), "inherits");
		assertThat(WorkloadClassifier.resolve(method)).isEqualTo(WorkloadClass.HEAVY_READ);
	}

	@Test
	void missingAnnotationDefaultsToInteractiveNormal() throws Exception {
		HandlerMethod method = handler(new BareController(), "plain");
		assertThat(WorkloadClassifier.resolve(method)).isEqualTo(WorkloadClass.INTERACTIVE_NORMAL);
		assertThat(WorkloadClassifier.resolve(new Object())).isEqualTo(WorkloadClass.INTERACTIVE_NORMAL);
	}

	private static HandlerMethod handler(Object bean, String methodName) throws Exception {
		return new HandlerMethod(bean, bean.getClass().getMethod(methodName));
	}

	@Workload(WorkloadClass.HEAVY_READ)
	static class TypedController {
		@Workload(WorkloadClass.INTERACTIVE_LIGHT)
		public void light() {}

		public void inherits() {}
	}

	static class BareController {
		public void plain() {}
	}
}
