package com.saga.be.service.jira;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-thread Jira evidence webhook worker. AbortPolicy: queue-full throws
 * {@link RejectedExecutionException} so the webhook can 503 and the provider retries.
 * Jobs are never silently discarded.
 */
@Component
@Profile("!test")
public class JiraEvidenceJobExecutor {

	public static final int MAX_CONCURRENCY = 1;
	public static final int QUEUE_CAPACITY = 200;

	private final ThreadPoolExecutor executor;

	public JiraEvidenceJobExecutor() {
		this(QUEUE_CAPACITY);
	}

	JiraEvidenceJobExecutor(int queueCapacity) {
		int capacity = Math.max(1, queueCapacity);
		ThreadFactory factory = new NamedFactory();
		this.executor = new ThreadPoolExecutor(
				MAX_CONCURRENCY,
				MAX_CONCURRENCY,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(capacity),
				factory,
				new ThreadPoolExecutor.AbortPolicy());
	}

	public void submit(Runnable job) {
		executor.execute(job);
	}

	int activeCount() {
		return executor.getActiveCount();
	}

	int queueRemainingCapacity() {
		return executor.getQueue().remainingCapacity();
	}

	int queueSize() {
		return executor.getQueue().size();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private static final class NamedFactory implements ThreadFactory {
		private final AtomicInteger sequence = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "jira-evidence-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
