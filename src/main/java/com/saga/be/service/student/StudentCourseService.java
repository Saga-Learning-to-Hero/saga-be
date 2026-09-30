package com.saga.be.service.student;

import com.saga.be.dto.student.StudentCoursePageResponse;
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
import com.saga.be.service.admin.AdminPaging;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
		return toResponses(sortedActiveEnrollments(userId));
	}

	/**
	 * Same rows and order as {@link #listMine}, optionally narrowed to one semester and/or a search
	 * over course code, subject code/name, class code and semester code (case-insensitive contains),
	 * then cut to one page. A student has few enrollments, so this pages in memory; team rows are
	 * loaded only for the page returned.
	 */
	@Transactional(readOnly = true)
	public StudentCoursePageResponse listMinePaged(
			UUID userId, UUID semesterId, String search, Integer page, Integer size) {
		int pageNumber = AdminPaging.page(page);
		int pageSize = AdminPaging.size(size);
		String needle = AdminPaging.search(search);
		String lowered = needle == null ? null : needle.toLowerCase(Locale.ROOT);
		List<CourseEnrollment> matching = sortedActiveEnrollments(userId).stream()
				.filter(row -> semesterId == null || semesterId.equals(semesterId(row.getCourse())))
				.filter(row -> lowered == null || matches(row.getCourse(), lowered))
				.toList();
		long from = (long) pageNumber * pageSize;
		List<CourseEnrollment> slice = from >= matching.size()
				? List.of()
				: matching.subList((int) from, (int) Math.min(from + pageSize, matching.size()));
		return new StudentCoursePageResponse(toResponses(slice), pageNumber, pageSize, matching.size());
	}

	private List<CourseEnrollment> sortedActiveEnrollments(UUID userId) {
		StudentProfile profile = students.findByUserAccount_Id(userId).orElse(null);
		if (profile == null) {
			throw new AcademicException(
					AcademicErrorCode.STUDENT_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Student is not ACTIVE in this course.");
		}
		return enrollments
				.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE)
				.stream()
				.sorted(courseOrder())
				.toList();
	}

	private List<StudentCourseResponse> toResponses(List<CourseEnrollment> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, TeamMember> memberships = members
				.findFetchedByCourseEnrollment_IdIn(rows.stream().map(CourseEnrollment::getId).toList())
				.stream()
				.collect(Collectors.toMap(
						member -> member.getCourseEnrollment().getId(), Function.identity(), (first, ignored) -> first));
		return rows.stream().map(row -> toResponse(row, memberships.get(row.getId()))).toList();
	}

	private static UUID semesterId(Course course) {
		return course == null || course.getSemester() == null ? null : course.getSemester().getId();
	}

	private static boolean matches(Course course, String loweredNeedle) {
		if (course == null) {
			return false;
		}
		Subject subject = course.getSubject();
		Semester semester = course.getSemester();
		for (String value : new String[] {
			course.getCourseCode(),
			course.getName(),
			subject == null ? null : subject.getSubjectCode(),
			subject == null ? null : subject.getName(),
			classCode(course),
			semester == null ? null : semester.getCode()
		}) {
			if (value != null && value.toLowerCase(Locale.ROOT).contains(loweredNeedle)) {
				return true;
			}
		}
		return false;
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
