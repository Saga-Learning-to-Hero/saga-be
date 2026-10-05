package com.saga.be.service.ai;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component("aiAnalysisDispatcher")
@Profile("!test")
public class AiAnalysisExecutor {
	private static final Logger log = LoggerFactory.getLogger(AiAnalysisExecutor.class);
	private final Executor executor; private final AiAnalysisExecutionService execution; private final AiAnalysisStateService state;
	public AiAnalysisExecutor(@Qualifier("aiAnalysisExecutor") Executor executor, AiAnalysisExecutionService execution, AiAnalysisStateService state) { this.executor = executor; this.execution = execution; this.state = state; }
	/**
	 * Runs already waiting in (or taken from) the queue. Recovery re-enqueues every QUEUED run each 30s;
	 * without this a backlog behind one slow worker filled the queue with copies of the same runs until
	 * real ones were rejected (AI_QUEUE_CAPACITY_EXCEEDED).
	 */
	private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

	/**
	 * Work nobody is waiting on (automatic reviews, a leader's backfill, retries, recovery) has its own
	 * lane, so a lecturer's report or a "Đánh giá lại" never queues behind a dozen background reviews
	 * of up to 100 s each. Without that lane (tests) everything shares the one executor.
	 */
	private Executor background;
	private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setBackground(@Qualifier("aiBackgroundExecutor") Executor background) {
		this.background = background;
	}

	/** Runs {@code work} so that every run it enqueues (also after its transaction commits) goes to the background lane. */
	public static <T> T inBackground(java.util.function.Supplier<T> work) {
		boolean outer = BACKGROUND.get();
		BACKGROUND.set(true);
		try {
			return work.get();
		} finally {
			BACKGROUND.set(outer);
		}
	}

	public void enqueue(UUID runId) {
		dispatch(runId, BACKGROUND.get() && background != null ? background : executor);
	}

	public void enqueueBackground(UUID runId) {
		dispatch(runId, background != null ? background : executor);
	}

	private void dispatch(UUID runId, Executor lane) {
		if (runId == null || !pending.add(runId)) return;
		try {
			lane.execute(() -> {
				try { execution.execute(runId); }
				finally { pending.remove(runId); }
			});
		}
		catch (RejectedExecutionException ex) { pending.remove(runId); state.failQueued(runId, "AI_QUEUE_CAPACITY_EXCEEDED", false); log.warn("ai analysis queue rejected runId={}", runId); }
	}

	int pendingCount() { return pending.size(); }
}
