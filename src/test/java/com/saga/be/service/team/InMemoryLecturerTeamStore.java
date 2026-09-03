package com.saga.be.service.team;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

final class InMemoryLecturerTeamStore implements LecturerTeamStore {

	final Map<UUID, Course> courses = new LinkedHashMap<>();
	final Map<UUID, CourseEnrollment> enrollments = new LinkedHashMap<>();
	final Map<UUID, Team> teams = new LinkedHashMap<>();
	final Map<UUID, TeamMember> members = new LinkedHashMap<>();

	@Override
	public Optional<Course> findCourse(UUID courseId) {
		return Optional.ofNullable(courses.get(courseId)).filter(course -> course.getDeletedAt() == null);
	}

	@Override
	public List<CourseEnrollment> listActiveEnrollments(UUID courseId) {
		return enrollments.values().stream()
				.filter(row -> row.getCourse() != null
						&& courseId.equals(row.getCourse().getId())
						&& row.getEnrollmentStatus() == EnrollmentStatus.ACTIVE)
				.sorted(Comparator.comparing(
						row -> row.getStudentProfile() == null || row.getStudentProfile().getStudentCode() == null
								? ""
								: row.getStudentProfile().getStudentCode(),
						String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	@Override
	public List<Team> listTeams(UUID courseId) {
		return teams.values().stream()
				.filter(row -> row.getCourse() != null && courseId.equals(row.getCourse().getId()))
				.sorted(Comparator.comparing(Team::getTeamNo, Comparator.nullsLast(Integer::compareTo)))
				.toList();
	}

	@Override
	public Optional<Team> findTeam(UUID courseId, Integer teamNo) {
		return teams.values().stream()
				.filter(row -> row.getCourse() != null
						&& courseId.equals(row.getCourse().getId())
						&& teamNo != null
						&& teamNo.equals(row.getTeamNo()))
				.findFirst();
	}

	@Override
	public Team saveTeam(Team team) {
		if (team.getId() == null) {
			team.setId(UUID.randomUUID());
		}
		teams.put(team.getId(), team);
		return team;
	}

	@Override
	public List<TeamMember> listMembers(UUID courseId) {
		return new ArrayList<>(members.values().stream()
				.filter(row -> row.getCourse() != null && courseId.equals(row.getCourse().getId()))
				.toList());
	}

	@Override
	public Optional<TeamMember> findMemberByEnrollment(UUID courseEnrollmentId) {
		return members.values().stream()
				.filter(row -> row.getCourseEnrollment() != null
						&& courseEnrollmentId.equals(row.getCourseEnrollment().getId()))
				.findFirst();
	}

	@Override
	public TeamMember saveMember(TeamMember member) {
		if (member.getId() == null) {
			member.setId(UUID.randomUUID());
		}
		members.put(member.getId(), member);
		return member;
	}

	@Override
	public <T> T inTransaction(Supplier<T> action) {
		Snapshot snapshot = snapshot();
		try {
			return action.get();
		} catch (RuntimeException | Error ex) {
			restore(snapshot);
			throw ex;
		}
	}

	void putCourse(Course course) {
		courses.put(course.getId(), course);
	}

	void putEnrollment(CourseEnrollment enrollment) {
		if (enrollment.getId() == null) {
			enrollment.setId(UUID.randomUUID());
		}
		enrollments.put(enrollment.getId(), enrollment);
	}

	private Snapshot snapshot() {
		return new Snapshot(copyTeams(), copyMembers());
	}

	private void restore(Snapshot snapshot) {
		teams.clear();
		teams.putAll(snapshot.teams);
		members.clear();
		members.putAll(snapshot.members);
	}

	private Map<UUID, Team> copyTeams() {
		Map<UUID, Team> copy = new LinkedHashMap<>();
		for (Team row : teams.values()) {
			Team clone = new Team();
			clone.setId(row.getId());
			clone.setCourse(row.getCourse());
			clone.setProject(row.getProject());
			clone.setTeamNo(row.getTeamNo());
			clone.setName(row.getName());
			copy.put(clone.getId(), clone);
		}
		return copy;
	}

	private Map<UUID, TeamMember> copyMembers() {
		Map<UUID, TeamMember> copy = new LinkedHashMap<>();
		for (TeamMember row : members.values()) {
			TeamMember clone = new TeamMember();
			clone.setId(row.getId());
			clone.setTeam(row.getTeam());
			clone.setCourse(row.getCourse());
			clone.setCourseEnrollment(row.getCourseEnrollment());
			clone.setRoleInTeam(row.getRoleInTeam());
			copy.put(clone.getId(), clone);
		}
		return copy;
	}

	private record Snapshot(Map<UUID, Team> teams, Map<UUID, TeamMember> members) {}
}
