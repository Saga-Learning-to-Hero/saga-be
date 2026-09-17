package com.saga.be.web;

import com.saga.be.workload.WorkloadClass;
import com.saga.be.workload.WorkloadClassifier;
import com.saga.be.workload.WorkloadRoutePatterns;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Attaches bounded {@code saga.workloadClass} and {@code saga.routePattern} before the controller runs.
 */
@Component
public class WorkloadClassInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		WorkloadClass workloadClass = WorkloadClassifier.resolve(handler);
		request.setAttribute(RequestPhaseAttrs.WORKLOAD_CLASS, workloadClass.name());
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		request.setAttribute(RequestPhaseAttrs.ROUTE_PATTERN, WorkloadRoutePatterns.normalize(pattern));
		return true;
	}
}
