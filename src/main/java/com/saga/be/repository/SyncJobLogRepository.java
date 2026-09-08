package com.saga.be.repository;

import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.integration.SyncJobLog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncJobLogRepository extends JpaRepository<SyncJobLog, UUID> {

	boolean existsByTargetSystemAndTargetIdAndStatus(String targetSystem, UUID targetId, SyncJobStatus status);

	List<SyncJobLog> findByTargetSystemAndTargetIdAndStatus(
			String targetSystem, UUID targetId, SyncJobStatus status);

	List<SyncJobLog> findTop20ByTargetIdOrderByStartedAtDesc(UUID targetId);

	Optional<SyncJobLog> findFirstByTargetSystemAndTargetIdOrderByStartedAtDesc(String targetSystem, UUID targetId);
}
