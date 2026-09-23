package com.saga.be.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

@ConfigurationProperties(prefix = "saga.tasks")
public class TaskDeadlineProperties {

	/** How far ahead of a task's due date counts as "due soon". Configurable, not hard-coded. */
	@DurationUnit(ChronoUnit.HOURS)
	private Duration dueSoonWindow = Duration.ofHours(72);

	private boolean deadlineWarningsEnabled = true;

	/** How often the deterministic deadline scan runs. */
	private Duration scanInterval = Duration.ofHours(6);

	private int scanBatchSize = 200;

	public Duration getDueSoonWindow() { return dueSoonWindow; }
	public void setDueSoonWindow(Duration dueSoonWindow) { this.dueSoonWindow = dueSoonWindow == null ? Duration.ofHours(72) : dueSoonWindow; }
	public boolean isDeadlineWarningsEnabled() { return deadlineWarningsEnabled; }
	public void setDeadlineWarningsEnabled(boolean deadlineWarningsEnabled) { this.deadlineWarningsEnabled = deadlineWarningsEnabled; }
	public Duration getScanInterval() { return scanInterval; }
	public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval == null ? Duration.ofHours(6) : scanInterval; }
	public int getScanBatchSize() { return scanBatchSize; }
	public void setScanBatchSize(int scanBatchSize) { this.scanBatchSize = Math.max(1, scanBatchSize); }
}
