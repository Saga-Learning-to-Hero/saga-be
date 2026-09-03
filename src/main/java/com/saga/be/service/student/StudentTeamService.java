package com.saga.be.service.student;

import com.saga.be.dto.team.StudentTeamMemberResponse;
import com.saga.be.dto.team.StudentTeamResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class StudentTeamService {

	public record ActiveTeamMembership(
			StudentProfile profile, CourseEnrollment enrollment, TeamMember member, Team team) {}

	private final StudentProfileRepository students;
	private final CourseEnrollmentRepository enrollments;
	private final TeamMemberRepository members;

	public StudentTeamService(
			StudentProfileRepository students, CourseEnrollmentRepository enrollments, TeamMemberRepository members) {
		this.students = students;
		this.enrollments = enrollments;
		this.members = members;
	}

	@Transactional(readOnly = true)
	public StudentTeamResponse myTeam(UUID userId, UUID courseId) {
		ActiveTeamMembership membership = requireActiveMembership(userId, courseId);
		Team team = membership.team();
		List<StudentTeamMemberResponse> roster = members.findByTeam_Id(team.getId()).stream()
				.sorted(Comparator.comparing((TeamMember member) -> member.getRoleInTeam() != RoleInTeam.LEADER)
						.thenComparing(member -> {
							CourseEnrollment row = member.getCourseEnrollment();
							StudentProfile person = row == null ? null : row.getStudentProfile();
							return person == null || person.getStudentCode() == null ? "" : person.getStudentCode();
						}, String.CASE_INSENSITIVE_ORDER))
				.map(StudentTeamService::toMember)
				.toList();
		Project project = team.getProject();
		return new StudentTeamResponse(
				team.getId(),
				team.getTeamNo() == null ? 0 : team.getTeamNo(),
				team.getName(),
				membership.member().getRoleInTeam() == null
						? null
						: membership.member().getRoleInTeam().name(),
				project == null ? null : project.getId(),
				roster);
	}

	public ActiveTeamMembership requireActiveMembership(UUID userId, UUID courseId) {
		StudentProfile profile = students.findByUserAccount_Id(userId).orElse(null);
		CourseEnrollment enrollment = profile == null
				? null
				: enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), courseId).orElse(null);
		if (enrollment == null || enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			throw new AcademicException(
					AcademicErrorCode.STUDENT_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Student is not ACTIVE in this course.");
		}
		TeamMember mine = members.findByCourseEnrollment_Id(enrollment.getId()).orElse(null);
		if (mine == null || mine.getTeam() == null) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Student is not assigned to a team.");
		}
		return new ActiveTeamMembership(profile, enrollment, mine, mine.getTeam());
	}

	private static StudentTeamMemberResponse toMember(TeamMember member) {
		CourseEnrollment enrollment = member.getCourseEnrollment();
		StudentProfile profile = enrollment == null ? null : enrollment.getStudentProfile();
		var user = profile == null ? null : profile.getUserAccount();
		return new StudentTeamMemberResponse(
				profile == null ? null : profile.getStudentCode(),
				user == null ? null : user.getFullName(),
				member.getRoleInTeam() == null ? null : member.getRoleInTeam().name());
	}
}
