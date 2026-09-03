package com.saga.be.repository;

import com.saga.be.entity.project.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	List<Team> findByCourse_IdOrderByTeamNoAsc(UUID courseId);

	Optional<Team> findByCourse_IdAndTeamNo(UUID courseId, Integer teamNo);
}
