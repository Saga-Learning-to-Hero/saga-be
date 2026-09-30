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

	/**
	 * Sprint-overlap checks run off the caller's thread: they are triggered from afterCommit
	 * callbacks that still hold their JDBC connection, and must not ask the small pool for a second
	 * one there. The check is idempotent, so a rejected run is simply redone on the next change.
	 */
	@Bean(name = "sprintOverlapExecutor")
	public Executor sprintOverlapExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setThreadNamePrefix("sprint-overlap-");
		executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
