package com.saga.be.repository;

import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.WorkSessionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskWorkSessionRepository extends JpaRepository<TaskWorkSession, UUID> {

	List<TaskWorkSession> findByTask_IdAndUser_IdAndStatus(UUID taskId, UUID userId, WorkSessionStatus status);

	boolean existsByProject_Id(UUID projectId);

	boolean existsByTask_Project_Id(UUID projectId);

	boolean existsByTask_Id(UUID taskId);

	long countByProject_Id(UUID projectId);

	long countByProject_IdAndUser_Id(UUID projectId, UUID userId);
}
