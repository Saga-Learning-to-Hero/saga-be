package com.saga.be.repository;

import com.saga.be.entity.attribution.ContributionConfirmation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContributionConfirmationRepository extends JpaRepository<ContributionConfirmation, UUID> {

	List<ContributionConfirmation> findByTask_IdAndUser_IdOrderByCreatedAtAsc(UUID taskId, UUID userId);

	boolean existsByProject_Id(UUID projectId);

	boolean existsByTask_Project_Id(UUID projectId);

	boolean existsByTask_Id(UUID taskId);
}
