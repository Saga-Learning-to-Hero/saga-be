package com.saga.be.service.student;

import com.saga.be.dto.student.StudentCourseResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class StudentCourseService {

	private final StudentProfileRepository students;
	private final CourseEnrollmentRepository enrollments;
	private final TeamMemberRepository members;

	public StudentCourseService(
			StudentProfileRepository students, CourseEnrollmentRepository enrollments, TeamMemberRepository members) {
		this.students = students;
		this.enrollments = enrollments;
		this.members = members;
	}

	@Transactional(readOnly = true)
	public List<StudentCourseResponse> listMine(UUID userId) {
		StudentProfile profile = students.findByUserAccount_Id(userId).orElse(null);
		if (profile == null) {
			throw new AcademicException(
					AcademicErrorCode.STUDENT_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Student is not ACTIVE in this course.");
		}
		List<CourseEnrollment> rows =
				enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE);
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, TeamMember> memberships = members
				.findFetchedByCourseEnrollment_IdIn(rows.stream().map(CourseEnrollment::getId).toList())
				.stream()
				.collect(Collectors.toMap(
						member -> member.getCourseEnrollment().getId(), Function.identity(), (first, ignored) -> first));
		return rows.stream()
				.sorted(courseOrder())
				.map(row -> toResponse(row, memberships.get(row.getId())))
				.toList();
	}

	private static Comparator<CourseEnrollment> courseOrder() {
		return Comparator.comparing(
						(CourseEnrollment row) -> semesterStart(row.getCourse()),
						Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(row -> safe(row.getCourse() == null ? null : row.getCourse().getCourseCode()), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(row -> safe(classCode(row.getCourse())), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(row -> row.getCourse() == null ? UUID.fromString("00000000-0000-0000-0000-000000000000") : row.getCourse().getId());
	}

	private static LocalDateTime semesterStart(Course course) {
		Semester semester = course == null ? null : course.getSemester();
		return semester == null ? null : semester.getStartDate();
	}

	private static String classCode(Course course) {
		AcademicClass academicClass = course == null ? null : course.getAcademicClass();
		return academicClass == null ? null : academicClass.getClassCode();
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static StudentCourseResponse toResponse(CourseEnrollment enrollment, TeamMember member) {
		Course course = enrollment.getCourse();
		Subject subject = course == null ? null : course.getSubject();
		AcademicClass academicClass = course == null ? null : course.getAcademicClass();
		Semester semester = course == null ? null : course.getSemester();
		Team team = member == null ? null : member.getTeam();
		Project project = team == null ? null : team.getProject();
		return new StudentCourseResponse(
				course == null ? null : course.getId(),
				course == null ? null : course.getCourseCode(),
				subject == null ? null : subject.getSubjectCode(),
				subject == null ? null : subject.getName(),
				academicClass == null ? null : academicClass.getClassCode(),
				semester == null ? null : semester.getCode(),
				semester == null ? null : semester.getName(),
				enrollment.getEnrollmentStatus() == null ? null : enrollment.getEnrollmentStatus().name(),
				team == null ? null : team.getId(),
				team == null ? null : team.getTeamNo(),
				team == null ? null : team.getName(),
				project == null ? null : project.getId());
	}
}
