package com.saga.be.service.team;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface LecturerTeamStore {

	Optional<Course> findCourse(UUID courseId);

	List<CourseEnrollment> listActiveEnrollments(UUID courseId);

	List<Team> listTeams(UUID courseId);

	Optional<Team> findTeam(UUID courseId, Integer teamNo);

	Team saveTeam(Team team);

	List<TeamMember> listMembers(UUID courseId);

	Optional<TeamMember> findMemberByEnrollment(UUID courseEnrollmentId);

	TeamMember saveMember(TeamMember member);

	default <T> T inTransaction(Supplier<T> action) {
		return action.get();
	}
}
