package com.saga.be.service.team;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JpaLecturerTeamStore implements LecturerTeamStore {

	private final CourseRepository courses;
	private final CourseEnrollmentRepository enrollments;
	private final TeamRepository teams;
	private final TeamMemberRepository members;

	public JpaLecturerTeamStore(
			CourseRepository courses,
			CourseEnrollmentRepository enrollments,
			TeamRepository teams,
			TeamMemberRepository members) {
		this.courses = courses;
		this.enrollments = enrollments;
		this.teams = teams;
		this.members = members;
	}

	@Override
	public Optional<Course> findCourse(UUID courseId) {
		return courses.findActiveFetchedById(courseId)
				.or(() -> courses.findById(courseId).filter(course -> course.getDeletedAt() == null));
	}

	@Override
	public List<CourseEnrollment> listActiveEnrollments(UUID courseId) {
		List<CourseEnrollment> fetched =
				enrollments.findFetchedByCourse_IdAndEnrollmentStatus(courseId, EnrollmentStatus.ACTIVE);
		if (!fetched.isEmpty()) {
			return fetched;
		}
		return enrollments.findByCourse_IdAndEnrollmentStatus(courseId, EnrollmentStatus.ACTIVE);
	}

	@Override
	public List<Team> listTeams(UUID courseId) {
		List<Team> fetched = teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId);
		if (!fetched.isEmpty()) {
			return fetched;
		}
		return teams.findByCourse_IdOrderByTeamNoAsc(courseId);
	}

	@Override
	public Optional<Team> findTeam(UUID courseId, Integer teamNo) {
		return teams.findByCourse_IdAndTeamNo(courseId, teamNo);
	}

	@Override
	public Optional<Team> findTeamById(UUID courseId, UUID teamId) {
		return teams.findById(teamId).filter(team -> team.getCourse() != null && courseId.equals(team.getCourse().getId()));
	}

	@Override
	public Optional<Team> findTeamByIdForUpdate(UUID teamId) {
		return teams.findByIdForUpdate(teamId);
	}

	@Override
	public Team saveTeam(Team team) {
		return teams.save(team);
	}

	@Override
	public List<TeamMember> listMembers(UUID courseId) {
		List<TeamMember> fetched = members.findFetchedByCourse_Id(courseId);
		if (!fetched.isEmpty()) {
			return fetched;
		}
		return members.findByCourse_Id(courseId);
	}

	@Override
	public List<TeamMember> listMembersByTeamId(UUID teamId) {
		List<TeamMember> fetched = members.findFetchedByTeam_Id(teamId);
		if (!fetched.isEmpty()) {
			return fetched;
		}
		return members.findByTeam_Id(teamId);
	}

	@Override
	public Optional<TeamMember> findMemberByEnrollment(UUID courseEnrollmentId) {
		return members.findByCourseEnrollment_Id(courseEnrollmentId);
	}

	@Override
	public Optional<TeamMember> findMemberById(UUID teamMemberId) {
		return members.findFetchedById(teamMemberId).or(() -> members.findById(teamMemberId));
	}

	@Override
	public TeamMember saveMember(TeamMember member) {
		return members.save(member);
	}
}
