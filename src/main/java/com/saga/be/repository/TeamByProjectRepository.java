package com.saga.be.repository;

import com.saga.be.entity.project.Team;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamByProjectRepository extends JpaRepository<Team, UUID> {

	Optional<Team> findByProject_Id(UUID projectId);
}
