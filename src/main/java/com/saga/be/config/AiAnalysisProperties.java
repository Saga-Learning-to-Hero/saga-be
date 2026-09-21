package com.saga.be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.ai")
public class AiAnalysisProperties {
	private int workers = 1;
	private int queueCapacity = 100;
	private int recoveryBatchSize = 50;
	private Duration staleRunningAfter = Duration.ofMinutes(15);
	public int getWorkers() { return workers; }
	public void setWorkers(int workers) { this.workers = Math.max(1, workers); }
	public int getQueueCapacity() { return queueCapacity; }
	public void setQueueCapacity(int queueCapacity) { this.queueCapacity = Math.max(1, queueCapacity); }
	public int getRecoveryBatchSize() { return recoveryBatchSize; }
	public void setRecoveryBatchSize(int recoveryBatchSize) { this.recoveryBatchSize = Math.max(1, recoveryBatchSize); }
	public Duration getStaleRunningAfter() { return staleRunningAfter; }
	public void setStaleRunningAfter(Duration staleRunningAfter) { this.staleRunningAfter = staleRunningAfter == null ? Duration.ofMinutes(15) : staleRunningAfter; }
}
