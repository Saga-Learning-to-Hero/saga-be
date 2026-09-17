package com.saga.be.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcTimingConfiguration implements WebMvcConfigurer {

	private final RequestPhaseInterceptor requestPhaseInterceptor;
	private final WorkloadClassInterceptor workloadClassInterceptor;

	public WebMvcTimingConfiguration(
			RequestPhaseInterceptor requestPhaseInterceptor, WorkloadClassInterceptor workloadClassInterceptor) {
		this.requestPhaseInterceptor = requestPhaseInterceptor;
		this.workloadClassInterceptor = workloadClassInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(workloadClassInterceptor);
		registry.addInterceptor(requestPhaseInterceptor);
	}
}
