package com.saga.be.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcTimingConfiguration implements WebMvcConfigurer {

	private final RequestPhaseInterceptor requestPhaseInterceptor;

	public WebMvcTimingConfiguration(RequestPhaseInterceptor requestPhaseInterceptor) {
		this.requestPhaseInterceptor = requestPhaseInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(requestPhaseInterceptor);
	}
}
