package com.saga.be.repository;

import com.saga.be.entity.assessment.ContributionOverride;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContributionOverrideRepository extends JpaRepository<ContributionOverride, UUID> {

	List<ContributionOverride> findByCourse_IdAndTeam_IdOrderByCreatedAtAsc(UUID courseId, UUID teamId);
}
