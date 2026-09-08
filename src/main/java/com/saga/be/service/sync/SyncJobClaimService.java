package com.saga.be.service.sync;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.repository.SyncJobLogRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single-node claim/enqueue guard for sync jobs. Per-key monitor + DB RUNNING check so same-JVM
 * double-clicks cannot enqueue twice or open two RUNNING jobs. Stale RUNNING rows (older than
 * {@link IntegrationProperties#getSyncJobStaleAfter()}) are failed so restart/crash cannot block
 * recovery forever. Multi-instance races remain check-then-act without a unique DB constraint (no V9).
 */
@Component
@Profile("!test")
public class SyncJobClaimService {

	static final String STALE_ERROR_CATEGORY = "SYNC_JOB_STALE";
	static final String STALE_FAILURE_STAGE = "reclaim";

	private static final Logger log = LoggerFactory.getLogger(SyncJobClaimService.class);

	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Boolean> reserved = new ConcurrentHashMap<>();
	private final SyncJobLogRepository syncJobs;
	private final IntegrationProperties properties;
	private final TransactionTemplate writes;

	public SyncJobClaimService(
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			PlatformTransactionManager transactionManager) {
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.writes = new TransactionTemplate(transactionManager);
	}

	/**
	 * Atomic (same JVM) enqueue reservation: blocks when a fresh RUNNING exists or already reserved.
	 * Stale RUNNING rows are failed first.
	 */
	public boolean tryReserveEnqueue(String targetSystem, UUID targetId) {
		String k = key(targetSystem, targetId);
		Object lock = locks.computeIfAbsent(k, ignored -> new Object());
		synchronized (lock) {
			failStaleRunning(targetSystem, targetId);
			if (Boolean.TRUE.equals(writes.execute(status -> syncJobs.existsByTargetSystemAndTargetIdAndStatus(
					targetSystem, targetId, SyncJobStatus.RUNNING)))) {
				return false;
			}
			return reserved.putIfAbsent(k, Boolean.TRUE) == null;
		}
	}

	public void releaseEnqueue(String targetSystem, UUID targetId) {
		reserved.remove(key(targetSystem, targetId));
	}

	public boolean isRunning(String targetSystem, UUID targetId) {
		Object lock = locks.computeIfAbsent(key(targetSystem, targetId), ignored -> new Object());
		synchronized (lock) {
			failStaleRunning(targetSystem, targetId);
			return Boolean.TRUE.equals(writes.execute(status -> syncJobs.existsByTargetSystemAndTargetIdAndStatus(
					targetSystem, targetId, SyncJobStatus.RUNNING)));
		}
	}

	public Optional<SyncJobLog> tryClaim(String targetSystem, UUID targetId, SyncJobType jobType) {
		String k = key(targetSystem, targetId);
		Object lock = locks.computeIfAbsent(k, ignored -> new Object());
		synchronized (lock) {
			try {
				return writes.execute(status -> {
					failStaleRunningInTx(targetSystem, targetId);
					if (syncJobs.existsByTargetSystemAndTargetIdAndStatus(
							targetSystem, targetId, SyncJobStatus.RUNNING)) {
						return Optional.empty();
					}
					SyncJobLog job = new SyncJobLog();
					job.setTargetSystem(targetSystem);
					job.setTargetId(targetId);
					job.setJobType(jobType);
					job.setStatus(SyncJobStatus.RUNNING);
					job.setStartedAt(LocalDateTime.now());
					job.setItemsProcessed(0);
					job.setItemsFailed(0);
					return Optional.of(syncJobs.save(job));
				});
			} finally {
				reserved.remove(k);
			}
		}
	}

	/** Marks a claimed job FAILED if still RUNNING (safe internal reason only). */
	public SyncJobLog markFailed(SyncJobLog job, String category, String stage) {
		return writes.execute(status -> {
			SyncJobLog row = job.getId() == null ? job : syncJobs.findById(job.getId()).orElse(job);
			if (row.getStatus() != SyncJobStatus.RUNNING && row.getCompletedAt() != null) {
				return row;
			}
			row.setStatus(SyncJobStatus.FAILED);
			row.setErrorCategory(category);
			row.setFailureStage(stage);
			row.setCompletedAt(LocalDateTime.now());
			if (row.getItemsProcessed() == null) {
				row.setItemsProcessed(0);
			}
			if (row.getItemsFailed() == null) {
				row.setItemsFailed(0);
			}
			return syncJobs.save(row);
		});
	}

	public SyncJobLog markSucceeded(SyncJobLog job, int processed) {
		return writes.execute(status -> {
			SyncJobLog row = job.getId() == null ? job : syncJobs.findById(job.getId()).orElse(job);
			row.setStatus(SyncJobStatus.SUCCEEDED);
			row.setItemsProcessed(processed);
			row.setItemsFailed(0);
			row.setCompletedAt(LocalDateTime.now());
			row.setErrorCategory(null);
			row.setFailureStage(null);
			return syncJobs.save(row);
		});
	}

	private void failStaleRunning(String targetSystem, UUID targetId) {
		writes.executeWithoutResult(status -> failStaleRunningInTx(targetSystem, targetId));
	}

	private void failStaleRunningInTx(String targetSystem, UUID targetId) {
		Duration staleAfter = properties.getSyncJobStaleAfter() == null
				? Duration.ofMinutes(30)
				: properties.getSyncJobStaleAfter();
		if (staleAfter.isNegative() || staleAfter.isZero()) {
			return;
		}
		LocalDateTime cutoff = LocalDateTime.now().minus(staleAfter);
		List<SyncJobLog> running =
				syncJobs.findByTargetSystemAndTargetIdAndStatus(targetSystem, targetId, SyncJobStatus.RUNNING);
		for (SyncJobLog job : running) {
			LocalDateTime started = job.getStartedAt() == null ? job.getCreatedAt() : job.getStartedAt();
			if (started != null && started.isBefore(cutoff)) {
				job.setStatus(SyncJobStatus.FAILED);
				job.setErrorCategory(STALE_ERROR_CATEGORY);
				job.setFailureStage(STALE_FAILURE_STAGE);
				job.setCompletedAt(LocalDateTime.now());
				if (job.getItemsProcessed() == null) {
					job.setItemsProcessed(0);
				}
				if (job.getItemsFailed() == null) {
					job.setItemsFailed(0);
				}
				syncJobs.save(job);
				log.warn(
						"stale sync job reclaimed targetSystem={} targetId={} jobIdPresent=true startedAt={}",
						targetSystem,
						targetId,
						started);
			}
		}
	}

	private static String key(String targetSystem, UUID targetId) {
		return targetSystem + ":" + targetId;
	}
}
