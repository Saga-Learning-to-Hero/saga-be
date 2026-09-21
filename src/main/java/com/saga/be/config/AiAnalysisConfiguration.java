package com.saga.be.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(AiAnalysisProperties.class)
public class AiAnalysisConfiguration {
	@Bean(name = "aiAnalysisExecutor")
	public Executor aiAnalysisExecutor(AiAnalysisProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkers());
		executor.setMaxPoolSize(properties.getWorkers());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("ai-analysis-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
