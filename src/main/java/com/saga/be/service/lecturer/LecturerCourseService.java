package com.saga.be.service.lecturer;

import com.saga.be.dto.academic.CoursePageResponse;
import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.dto.team.LecturerActiveRosterEntryResponse;
import com.saga.be.dto.team.LecturerActiveRosterResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.service.admin.AdminPaging;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class LecturerCourseService {

	private final LecturerCourseAuthorization authorization;
	private final CourseRepository courses;
	private final LecturerProfileRepository lecturers;
	private final CourseEnrollmentRepository enrollments;

	public LecturerCourseService(
			LecturerCourseAuthorization authorization,
			CourseRepository courses,
			LecturerProfileRepository lecturers,
			CourseEnrollmentRepository enrollments) {
		this.authorization = authorization;
		this.courses = courses;
		this.lecturers = lecturers;
		this.enrollments = enrollments;
	}

	@Transactional(readOnly = true)
	public List<CourseResponse> listCourses(UserAccount actor) {
		List<Course> rows;
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			rows = courses.search(null, null, null, null);
		} else if (actor == null) {
			rows = List.of();
		} else {
			LecturerProfile profile = lecturers.findByUserAccount_Id(actor.getId()).orElse(null);
			if (profile == null) {
				return List.of();
			}
			rows = courses.search(null, null, null, profile.getId());
		}
		return rows.stream().map(this::toCourse).toList();
	}

	/**
	 * Same scope and order as {@link #listCourses} (a lecturer's own courses; every course for
	 * ADMIN), paged in the database and optionally narrowed by semester and search text.
	 */
	@Transactional(readOnly = true)
	public CoursePageResponse listCoursesPaged(
			UserAccount actor, UUID semesterId, String search, Integer page, Integer size) {
		int pageNumber = AdminPaging.page(page);
		int pageSize = AdminPaging.size(size);
		UUID lecturerId;
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			lecturerId = null;
		} else {
			LecturerProfile profile = actor == null ? null : lecturers.findByUserAccount_Id(actor.getId()).orElse(null);
			if (profile == null) {
				return new CoursePageResponse(List.of(), pageNumber, pageSize, 0);
			}
			lecturerId = profile.getId();
		}
		Page<UUID> ids = courses.findLecturerPickerPageIds(
				lecturerId, semesterId, AdminPaging.likeSearch(search), PageRequest.of(pageNumber, pageSize));
		List<CourseResponse> items = List.of();
		if (!ids.getContent().isEmpty()) {
			Map<UUID, Course> byId = new HashMap<>();
			for (Course course : courses.findFetchedByIdIn(ids.getContent())) {
				byId.put(course.getId(), course);
			}
			items = ids.getContent().stream()
					.map(byId::get)
					.filter(Objects::nonNull)
					.map(this::toCourse)
					.toList();
		}
		return new CoursePageResponse(items, pageNumber, pageSize, ids.getTotalElements());
	}

	@Transactional(readOnly = true)
	public CourseResponse getCourse(UserAccount actor, UUID courseId) {
		return toCourse(authorization.requireCourse(actor, courseId));
	}

	@Transactional(readOnly = true)
	public LecturerActiveRosterResponse getActiveRoster(UserAccount actor, UUID courseId) {
		Course course = authorization.requireCourse(actor, courseId);
		String classCode = course.getAcademicClass() == null ? null : course.getAcademicClass().getClassCode();
		List<CourseEnrollment> active = enrollments.findFetchedByCourse_IdAndEnrollmentStatus(
				courseId, EnrollmentStatus.ACTIVE);
		if (active.isEmpty()) {
			active = enrollments.findByCourse_IdAndEnrollmentStatus(courseId, EnrollmentStatus.ACTIVE);
		}
		List<LecturerActiveRosterEntryResponse> entries = active.stream()
				.map(enrollment -> toRosterEntry(enrollment, classCode))
				.toList();
		return new LecturerActiveRosterResponse(course.getId(), classCode, entries.size(), entries);
	}

	private LecturerActiveRosterEntryResponse toRosterEntry(CourseEnrollment enrollment, String classCode) {
		var profile = enrollment.getStudentProfile();
		var user = profile == null ? null : profile.getUserAccount();
		return new LecturerActiveRosterEntryResponse(
				enrollment.getId(),
				profile == null ? null : profile.getId(),
				profile == null ? null : profile.getStudentCode(),
				user == null ? null : user.getFullName(),
				user == null ? null : user.getAvatarUrl(),
				user == null ? null : user.getEmail(),
				classCode);
	}

	private CourseResponse toCourse(Course course) {
		AcademicClass academicClass = course.getAcademicClass();
		Semester semester = course.getSemester();
		Subject subject = course.getSubject();
		SubjectSyllabusVersion syllabus = course.getSyllabusVersion();
		LecturerProfile lecturer = course.getInstructor();
		UserAccount lecturerUser = lecturer == null ? null : lecturer.getUserAccount();
		return new CourseResponse(
				course.getId(),
				course.getCourseCode(),
				course.getName(),
				academicClass == null ? null : academicClass.getId(),
				academicClass == null ? null : academicClass.getClassCode(),
				academicClass == null ? null : academicClass.getName(),
				semester == null ? null : semester.getId(),
				semester == null ? null : semester.getCode(),
				semester == null ? null : semester.getName(),
				subject == null ? null : subject.getId(),
				subject == null ? null : subject.getSubjectCode(),
				subject == null ? null : subject.getName(),
				course.getSyllabusVersionId(),
				syllabus == null ? null : syllabus.getVersionLabel(),
				syllabus == null ? null : syllabus.getStatus(),
				lecturer == null ? null : lecturer.getId(),
				lecturerUser == null ? null : lecturerUser.getId(),
				lecturerUser == null ? null : lecturerUser.getEmail(),
				lecturerUser == null ? null : lecturerUser.getFullName(),
				course.getCreatedAt(),
				course.getUpdatedAt());
	}
}
