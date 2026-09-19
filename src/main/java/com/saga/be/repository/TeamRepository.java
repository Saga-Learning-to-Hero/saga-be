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

	@Query(
			"""
			SELECT t FROM Team t
			LEFT JOIN FETCH t.project
			WHERE t.course.id = :courseId
			ORDER BY t.teamNo ASC
			""")
	List<Team> findFetchedByCourse_IdOrderByTeamNoAsc(@Param("courseId") UUID courseId);

	Optional<Team> findByCourse_IdAndTeamNo(UUID courseId, Integer teamNo);

	Optional<Team> findByProject_Id(UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Team t where t.id = :id")
	Optional<Team> findByIdForUpdate(@Param("id") UUID id);

	@Query(
			"""
			SELECT t FROM Team t
			JOIN FETCH t.course c
			LEFT JOIN FETCH c.instructor i
			LEFT JOIN FETCH i.userAccount
			LEFT JOIN FETCH t.project
			WHERE t.id = :id
			""")
	Optional<Team> findFetchedById(@Param("id") UUID id);

	@Query(
			"""
			SELECT t FROM Team t
			JOIN FETCH t.course
			LEFT JOIN FETCH t.project
			WHERE t.id = :teamId AND t.course.id = :courseId
			""")
	Optional<Team> findFetchedByIdAndCourse_Id(@Param("teamId") UUID teamId, @Param("courseId") UUID courseId);

	@Query(
			"""
			select count(t.id)
			from Team t
			join t.course c
			where c.semester.id = :semesterId
			  and c.deletedAt is null
			""")
	long countByCourseSemester(@Param("semesterId") UUID semesterId);

	/**
	 * Canonical connected-team count for admin dashboard / unconnected-team alert.
	 *
	 * <p>Counts <strong>teams</strong>, not repositories. A team is connected when it has a
	 * Project, a Jira integration with {@code connectionStatus = ACTIVE}, and {@code EXISTS} at
	 * least one GitRepo with {@code connectionStatus = ACTIVE}. Multiple ACTIVE repos on one
	 * project still contribute one team ({@code count(distinct t.id)} + {@code EXISTS}). Legacy
	 * {@code CONNECTED} is not sufficient.
	 */
	@Query(
			"""
			select count(distinct t.id)
			from Team t
			join t.course c
			where c.semester.id = :semesterId
			  and c.deletedAt is null
			  and t.project is not null
			  and exists (
			    select 1 from JiraIntegration j
			    where j.project = t.project
			      and j.connectionStatus = com.saga.be.entity.enums.IntegrationStatus.ACTIVE
			  )
			  and exists (
			    select 1 from GitRepo r
			    where r.project = t.project
			      and r.connectionStatus = com.saga.be.entity.enums.IntegrationStatus.ACTIVE
			  )
			""")
	long countConnectedByCourseSemester(@Param("semesterId") UUID semesterId);
}
