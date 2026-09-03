package com.saga.be.repository;

import com.saga.be.entity.project.TeamMember;
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
			JOIN FETCH m.team
			JOIN FETCH m.courseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			WHERE m.course.id = :courseId
			""")
	List<TeamMember> findFetchedByCourse_Id(@Param("courseId") UUID courseId);
}
