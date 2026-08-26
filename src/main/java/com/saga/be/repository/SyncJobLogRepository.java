package com.saga.be.repository;

import com.saga.be.entity.integration.SyncJobLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncJobLogRepository extends JpaRepository<SyncJobLog, UUID> {}
