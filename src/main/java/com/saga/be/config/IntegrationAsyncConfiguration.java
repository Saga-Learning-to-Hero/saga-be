package com.saga.be.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class IntegrationAsyncConfiguration {

	static final int MAX_ACTIVE_BACKGROUND_SYNC = 1;
	static final int QUEUE_CAPACITY = 200;

	@Bean(name = "integrationSyncExecutor")
	public Executor integrationSyncExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(MAX_ACTIVE_BACKGROUND_SYNC);
		executor.setMaxPoolSize(MAX_ACTIVE_BACKGROUND_SYNC);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setThreadNamePrefix("integration-sync-");
		// AbortPolicy: HTTP enqueue can releaseEnqueue; never silently drop work.
		executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
