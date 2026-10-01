package com.saga.be.repository;

import com.saga.be.entity.delay.TaskChangeLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskChangeLogRepository extends JpaRepository<TaskChangeLog, UUID> {

	List<TaskChangeLog> findByTask_IdOrderByChangedAtAsc(UUID taskId);
}
