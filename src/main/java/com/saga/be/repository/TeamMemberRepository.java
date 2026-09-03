package com.saga.be.repository;

import com.saga.be.entity.project.TeamMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

	List<TeamMember> findByTeam_Id(UUID teamId);

	Optional<TeamMember> findByCourseEnrollment_Id(UUID courseEnrollmentId);

	List<TeamMember> findByCourse_Id(UUID courseId);

	@Query(
			"""
			SELECT m FROM TeamMember m
			JOIN FETCH m.team t
			JOIN FETCH t.course
			JOIN FETCH m.courseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			WHERE m.team.id = :teamId
			""")
	List<TeamMember> findFetchedByTeam_Id(@Param("teamId") UUID teamId);

	@Query(
			"""
			SELECT m FROM TeamMember m
			JOIN FETCH m.team
			JOIN FETCH m.courseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			WHERE m.course.id = :courseId
			""")
	List<TeamMember> findFetchedByCourse_Id(@Param("courseId") UUID courseId);

	@Query(
			"""
			SELECT m FROM TeamMember m
			JOIN FETCH m.team t
			LEFT JOIN FETCH t.project
			JOIN FETCH m.courseEnrollment
			WHERE m.courseEnrollment.id IN :enrollmentIds
			""")
	List<TeamMember> findFetchedByCourseEnrollment_IdIn(@Param("enrollmentIds") Collection<UUID> enrollmentIds);
}
