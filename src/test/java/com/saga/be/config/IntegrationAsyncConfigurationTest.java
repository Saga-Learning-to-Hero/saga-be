package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class IntegrationAsyncConfigurationTest {

	@Test
	void executorIsSingleWorkerWithBoundedQueue() {
		ThreadPoolTaskExecutor executor = executor();
		try {
			assertThat(executor.getCorePoolSize())
					.isEqualTo(IntegrationAsyncConfiguration.MAX_ACTIVE_BACKGROUND_SYNC)
					.isEqualTo(1);
			assertThat(executor.getMaxPoolSize()).isEqualTo(1);
			assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
					.isEqualTo(IntegrationAsyncConfiguration.QUEUE_CAPACITY);
		} finally {
			executor.destroy();
		}
	}

	@Test
	void neverRunsMoreThanOneSyncJobConcurrently() throws Exception {
		ThreadPoolTaskExecutor executor = executor();
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch holdFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		AtomicInteger concurrent = new AtomicInteger();
		AtomicInteger max = new AtomicInteger();
		try {
			executor.execute(() -> {
				max.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
				firstStarted.countDown();
				await(holdFirst);
				concurrent.decrementAndGet();
			});
			assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
			executor.execute(() -> {
				max.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
				secondStarted.countDown();
				concurrent.decrementAndGet();
			});
			assertThat(secondStarted.await(80, TimeUnit.MILLISECONDS)).isFalse();
			holdFirst.countDown();
			assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(max.get()).isEqualTo(1);
		} finally {
			holdFirst.countDown();
			executor.destroy();
		}
	}

	private static ThreadPoolTaskExecutor executor() {
		return (ThreadPoolTaskExecutor) new IntegrationAsyncConfiguration().integrationSyncExecutor();
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
