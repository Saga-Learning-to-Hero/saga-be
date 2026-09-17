package com.saga.be.service.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JiraEvidenceJobExecutorTest {

	private JiraEvidenceJobExecutor executor;

	@AfterEach
	void stop() {
		if (executor != null) {
			executor.shutdown();
		}
	}

	@Test
	void neverRunsMoreThanOneJobConcurrently() throws Exception {
		executor = new JiraEvidenceJobExecutor(8);
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch holdFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		AtomicInteger concurrent = new AtomicInteger();
		AtomicInteger max = new AtomicInteger();
		AtomicReference<String> threadName = new AtomicReference<>();
		executor.submit(() -> {
			threadName.set(Thread.currentThread().getName());
			max.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
			firstStarted.countDown();
			await(holdFirst);
			concurrent.decrementAndGet();
		});
		assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(threadName.get()).startsWith("jira-evidence-");
		executor.submit(() -> {
			max.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
			secondStarted.countDown();
			concurrent.decrementAndGet();
		});
		assertThat(secondStarted.await(80, TimeUnit.MILLISECONDS)).isFalse();
		holdFirst.countDown();
		assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(max.get()).isEqualTo(1);
		assertThat(JiraEvidenceJobExecutor.MAX_CONCURRENCY).isEqualTo(1);
	}

	@Test
	void queueFullRejectsWithoutDroppingTheRunnable() throws Exception {
		executor = new JiraEvidenceJobExecutor(1);
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch holdFirst = new CountDownLatch(1);
		executor.submit(() -> {
			firstStarted.countDown();
			await(holdFirst);
		});
		assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
		CountDownLatch queuedDone = new CountDownLatch(1);
		executor.submit(queuedDone::countDown);
		AtomicBoolean rejectedJobRan = new AtomicBoolean();
		Runnable rejectedJob = () -> rejectedJobRan.set(true);
		assertThatThrownBy(() -> executor.submit(rejectedJob)).isInstanceOf(RejectedExecutionException.class);
		assertThat(rejectedJobRan.get()).isFalse();
		holdFirst.countDown();
		assertThat(queuedDone.await(2, TimeUnit.SECONDS)).isTrue();
		CountDownLatch recovered = new CountDownLatch(1);
		executor.submit(() -> {
			rejectedJob.run();
			recovered.countDown();
		});
		assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(rejectedJobRan.get()).isTrue();
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
