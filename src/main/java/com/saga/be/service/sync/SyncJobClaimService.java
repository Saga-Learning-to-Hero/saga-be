package com.saga.be.service.sync;

import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.repository.SyncJobLogRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single-node claim/enqueue guard for sync jobs. Per-key monitor + DB RUNNING check so same-JVM
 * double-clicks cannot enqueue twice or open two RUNNING jobs. Multi-instance races remain
 * check-then-act without a unique DB constraint (no V9).
 */
@Component
@Profile("!test")
public class SyncJobClaimService {

	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Boolean> reserved = new ConcurrentHashMap<>();
	private final SyncJobLogRepository syncJobs;
	private final TransactionTemplate writes;

	public SyncJobClaimService(SyncJobLogRepository syncJobs, PlatformTransactionManager transactionManager) {
		this.syncJobs = syncJobs;
		this.writes = new TransactionTemplate(transactionManager);
	}

	/**
	 * Atomic (same JVM) enqueue reservation: blocks when RUNNING or already reserved for enqueue.
	 */
	public boolean tryReserveEnqueue(String targetSystem, UUID targetId) {
		String k = key(targetSystem, targetId);
		Object lock = locks.computeIfAbsent(k, ignored -> new Object());
		synchronized (lock) {
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

	private static String key(String targetSystem, UUID targetId) {
		return targetSystem + ":" + targetId;
	}
}
