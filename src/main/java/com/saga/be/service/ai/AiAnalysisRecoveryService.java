package com.saga.be.service.ai;

import com.saga.be.config.AiAnalysisProperties;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Startup/scheduled recovery: stale RUNNING becomes FAILED; durable QUEUED rows are re-enqueued. */
@Component
@Profile("!test")
public class AiAnalysisRecoveryService {
	private final AiAnalysisStateService state; private final AiAnalysisExecutor executor; private final AiAnalysisProperties properties;
	public AiAnalysisRecoveryService(AiAnalysisStateService state, AiAnalysisExecutor executor, AiAnalysisProperties properties) { this.state = state; this.executor = executor; this.properties = properties; }
	@Scheduled(fixedDelayString = "${saga.ai.recovery-delay:PT30S}")
	public void recover() {
		state.failStaleRunning(LocalDateTime.now().minus(properties.getStaleRunningAfter()));
		for (var id : state.queuedIds(properties.getRecoveryBatchSize())) executor.enqueue(id);
	}
}
