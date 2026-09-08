package com.saga.be.repository;

import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncJobLogRepository extends JpaRepository<SyncJobLog, UUID> {

	List<SyncJobLog> findByTargetSystemAndJobTypeAndStatus(String targetSystem, SyncJobType jobType, SyncJobStatus status);
}
