package com.saga.be.service.ai;

import java.util.UUID;
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
	public void enqueue(UUID runId) {
		try { executor.execute(() -> execution.execute(runId)); }
		catch (RejectedExecutionException ex) { state.failQueued(runId, "AI_QUEUE_CAPACITY_EXCEEDED", false); log.warn("ai analysis queue rejected runId={}", runId); }
	}
}
