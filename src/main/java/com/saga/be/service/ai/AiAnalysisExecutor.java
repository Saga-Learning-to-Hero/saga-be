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

	public void enqueue(UUID runId) {
		if (runId == null || !pending.add(runId)) return;
		try {
			executor.execute(() -> {
				try { execution.execute(runId); }
				finally { pending.remove(runId); }
			});
		}
		catch (RejectedExecutionException ex) { pending.remove(runId); state.failQueued(runId, "AI_QUEUE_CAPACITY_EXCEEDED", false); log.warn("ai analysis queue rejected runId={}", runId); }
	}

	int pendingCount() { return pending.size(); }
}
