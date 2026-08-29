package com.saga.be.service.academic;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.account.LecturerProfile;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryAcademicRuntimeStore implements AcademicRuntimeStore {

	final Map<UUID, Semester> semesters = new ConcurrentHashMap<>();
	final Map<UUID, AcademicClass> classes = new ConcurrentHashMap<>();
	final Map<UUID, Course> courses = new ConcurrentHashMap<>();
	final Map<UUID, Subject> subjects = new ConcurrentHashMap<>();
	final Map<UUID, SubjectSyllabusVersion> syllabi = new ConcurrentHashMap<>();
	final Map<UUID, LecturerProfile> lecturers = new ConcurrentHashMap<>();
	final Set<UUID> enrollmentCourseIds = ConcurrentHashMap.newKeySet();
	final Set<UUID> projectCourseIds = ConcurrentHashMap.newKeySet();
	final ActiveSemesterSetting active = new ActiveSemesterSetting();

	InMemoryAcademicRuntimeStore() {
		active.setSingletonId((byte) 1);
		active.setUpdatedAt(LocalDateTime.now());
	}

	@Override
	public Optional<Semester> findSemester(UUID id) {
		return Optional.ofNullable(semesters.get(id)).filter(row -> row.getDeletedAt() == null);
	}

	@Override
	public Optional<Semester> findSemesterByCode(String code) {
		return semesters.values().stream()
				.filter(row -> row.getDeletedAt() == null && code.equals(row.getCode()))
				.findFirst();
	}

	@Override
	public boolean semesterCodeTaken(String code, UUID excludeId) {
		return semesters.values().stream()
				.anyMatch(row -> code.equals(row.getCode()) && (excludeId == null || !excludeId.equals(row.getId())));
	}

	@Override
	public boolean semesterHasClasses(UUID semesterId) {
		return classes.values().stream()
				.anyMatch(row -> row.getSemester() != null && semesterId.equals(row.getSemester().getId()));
	}

	@Override
	public boolean semesterHasCourses(UUID semesterId) {
		return courses.values().stream()
				.anyMatch(row -> row.getSemester() != null && semesterId.equals(row.getSemester().getId()));
	}

	@Override
	public List<Semester> listSemesters() {
		return semesters.values().stream()
				.filter(row -> row.getDeletedAt() == null)
				.sorted(Comparator.comparing(Semester::getStartDate, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	@Override
	public Semester saveSemester(Semester semester) {
		if (semester.getId() == null) {
			semester.setId(UUID.randomUUID());
		}
		semesters.put(semester.getId(), semester);
		return semester;
	}

	@Override
	public ActiveSemesterSetting getActiveSemesterSetting() {
		return active;
	}

	@Override
	public ActiveSemesterSetting saveActiveSemesterSetting(ActiveSemesterSetting setting) {
		active.setSemester(setting.getSemester());
		active.setUpdatedBy(setting.getUpdatedBy());
		active.setUpdatedAt(setting.getUpdatedAt() == null ? LocalDateTime.now() : setting.getUpdatedAt());
		return active;
	}

	@Override
	public Optional<AcademicClass> findClass(UUID id) {
		return Optional.ofNullable(classes.get(id)).filter(row -> row.getDeletedAt() == null);
	}

	@Override
	public boolean classCodeTaken(UUID semesterId, String classCode, UUID excludeId) {
		return classes.values().stream()
				.anyMatch(row -> row.getSemester() != null
						&& semesterId.equals(row.getSemester().getId())
						&& classCode.equals(row.getClassCode())
						&& (excludeId == null || !excludeId.equals(row.getId())));
	}

	@Override
	public boolean classHasCourses(UUID classId) {
		return courses.values().stream()
				.anyMatch(row -> row.getAcademicClass() != null && classId.equals(row.getAcademicClass().getId()));
	}

	@Override
	public List<AcademicClass> listClasses(UUID semesterId) {
		return classes.values().stream()
				.filter(row -> row.getDeletedAt() == null)
				.filter(row -> semesterId == null
						|| (row.getSemester() != null && semesterId.equals(row.getSemester().getId())))
				.sorted(Comparator.comparing(AcademicClass::getClassCode))
				.toList();
	}

	@Override
	public AcademicClass saveClass(AcademicClass academicClass) {
		if (academicClass.getId() == null) {
			academicClass.setId(UUID.randomUUID());
		}
		classes.put(academicClass.getId(), academicClass);
		return academicClass;
	}

	@Override
	public Optional<Course> findCourse(UUID id) {
		return Optional.ofNullable(courses.get(id)).filter(row -> row.getDeletedAt() == null);
	}

	@Override
	public boolean courseOfferingTaken(UUID academicClassId, UUID subjectId) {
		return courses.values().stream()
				.anyMatch(row -> row.getAcademicClass() != null
						&& row.getSubject() != null
						&& academicClassId.equals(row.getAcademicClass().getId())
						&& subjectId.equals(row.getSubject().getId()));
	}

	@Override
	public List<Course> listCourses(UUID semesterId, UUID academicClassId, UUID subjectId, UUID lecturerId) {
		return courses.values().stream()
				.filter(row -> row.getDeletedAt() == null)
				.filter(row -> semesterId == null
						|| (row.getSemester() != null && semesterId.equals(row.getSemester().getId())))
				.filter(row -> academicClassId == null
						|| (row.getAcademicClass() != null && academicClassId.equals(row.getAcademicClass().getId())))
				.filter(row -> subjectId == null
						|| (row.getSubject() != null && subjectId.equals(row.getSubject().getId())))
				.filter(row -> lecturerId == null
						|| (row.getInstructor() != null && lecturerId.equals(row.getInstructor().getId())))
				.sorted(Comparator.comparing(Course::getName, Comparator.nullsLast(String::compareTo)))
				.toList();
	}

	@Override
	public Course saveCourse(Course course) {
		if (course.getId() == null) {
			course.setId(UUID.randomUUID());
		}
		courses.put(course.getId(), course);
		return course;
	}

	@Override
	public boolean courseHasEnrollments(UUID courseId) {
		return enrollmentCourseIds.contains(courseId);
	}

	@Override
	public boolean courseHasProjects(UUID courseId) {
		return projectCourseIds.contains(courseId);
	}

	@Override
	public Optional<Subject> findSubject(UUID id) {
		return Optional.ofNullable(subjects.get(id));
	}

	@Override
	public Optional<SubjectSyllabusVersion> findSyllabus(UUID id) {
		return Optional.ofNullable(syllabi.get(id));
	}

	@Override
	public Optional<LecturerProfile> findLecturer(UUID id) {
		return Optional.ofNullable(lecturers.get(id));
	}
}
