package com.saga.be.repository;

import com.saga.be.entity.jira.JiraTaskFailoverRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JiraTaskFailoverRunRepository extends JpaRepository<JiraTaskFailoverRun, UUID> {

	Optional<JiraTaskFailoverRun> findByIdAndProject_Id(UUID id, UUID projectId);
}
