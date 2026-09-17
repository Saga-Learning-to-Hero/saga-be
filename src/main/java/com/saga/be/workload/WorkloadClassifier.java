package com.saga.be.workload;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/** Resolves {@link Workload} from a Spring MVC {@link HandlerMethod}. */
public final class WorkloadClassifier {

	private WorkloadClassifier() {}

	public static WorkloadClass resolve(Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return WorkloadClass.INTERACTIVE_NORMAL;
		}
		return resolve(method);
	}

	public static WorkloadClass resolve(HandlerMethod handler) {
		Workload method = handler.getMethodAnnotation(Workload.class);
		if (method != null) {
			return method.value();
		}
		Workload type = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Workload.class);
		if (type != null) {
			return type.value();
		}
		return WorkloadClass.INTERACTIVE_NORMAL;
	}
}
