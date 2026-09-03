package com.saga.be.repository;

import com.saga.be.entity.project.Team;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	List<Team> findByCourse_IdOrderByTeamNoAsc(UUID courseId);

	Optional<Team> findByCourse_IdAndTeamNo(UUID courseId, Integer teamNo);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Team t where t.id = :id")
	Optional<Team> findByIdForUpdate(@Param("id") UUID id);
}
