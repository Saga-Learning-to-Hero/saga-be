package com.saga.be.service.academic;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.SubjectSyllabusVersionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JpaAcademicRuntimeStore implements AcademicRuntimeStore {

	private static final byte SINGLETON_ID = 1;

	private final SemesterRepository semesters;
	private final ActiveSemesterSettingRepository activeSettings;
	private final AcademicClassRepository classes;
	private final CourseRepository courses;
	private final CourseEnrollmentRepository enrollments;
	private final ProjectRepository projects;
	private final SubjectRepository subjects;
	private final SubjectSyllabusVersionRepository syllabi;
	private final LecturerProfileRepository lecturers;

	public JpaAcademicRuntimeStore(
			SemesterRepository semesters,
			ActiveSemesterSettingRepository activeSettings,
			AcademicClassRepository classes,
			CourseRepository courses,
			CourseEnrollmentRepository enrollments,
			ProjectRepository projects,
			SubjectRepository subjects,
			SubjectSyllabusVersionRepository syllabi,
			LecturerProfileRepository lecturers) {
		this.semesters = semesters;
		this.activeSettings = activeSettings;
		this.classes = classes;
		this.courses = courses;
		this.enrollments = enrollments;
		this.projects = projects;
		this.subjects = subjects;
		this.syllabi = syllabi;
		this.lecturers = lecturers;
	}

	@Override
	public Optional<Semester> findSemester(UUID id) {
		return semesters.findById(id).filter(semester -> semester.getDeletedAt() == null);
	}

	@Override
	public Optional<Semester> findSemesterByCode(String code) {
		return semesters.findByCode(code).filter(semester -> semester.getDeletedAt() == null);
	}

	@Override
	public boolean semesterCodeTaken(String code, UUID excludeId) {
		if (excludeId == null) {
			return semesters.existsByCode(code);
		}
		return semesters.existsByCodeAndIdNot(code, excludeId);
	}

	@Override
	public boolean semesterHasClasses(UUID semesterId) {
		return classes.existsBySemester_Id(semesterId);
	}

	@Override
	public boolean semesterHasCourses(UUID semesterId) {
		return courses.existsBySemester_Id(semesterId);
	}

	@Override
	public List<Semester> listSemesters() {
		return semesters.findByDeletedAtIsNullOrderByStartDateDesc();
	}

	@Override
	public Semester saveSemester(Semester semester) {
		return semesters.save(semester);
	}

	@Override
	public ActiveSemesterSetting getActiveSemesterSetting() {
		return activeSettings.findById(SINGLETON_ID).orElseGet(() -> {
			ActiveSemesterSetting created = new ActiveSemesterSetting();
			created.setSingletonId(SINGLETON_ID);
			created.setUpdatedAt(LocalDateTime.now());
			return activeSettings.save(created);
		});
	}

	@Override
	public ActiveSemesterSetting saveActiveSemesterSetting(ActiveSemesterSetting setting) {
		return activeSettings.save(setting);
	}

	@Override
	public Optional<AcademicClass> findClass(UUID id) {
		return classes.findById(id).filter(row -> row.getDeletedAt() == null);
	}

	@Override
	public boolean classCodeTaken(UUID semesterId, String classCode, UUID excludeId) {
		if (excludeId == null) {
			return classes.existsBySemester_IdAndClassCode(semesterId, classCode);
		}
		return classes.existsBySemester_IdAndClassCodeAndIdNot(semesterId, classCode, excludeId);
	}

	@Override
	public boolean classHasCourses(UUID classId) {
		return courses.existsByAcademicClass_Id(classId);
	}

	@Override
	public List<AcademicClass> listClasses(UUID semesterId) {
		if (semesterId == null) {
			return classes.findByDeletedAtIsNullOrderByClassCodeAsc();
		}
		return classes.findBySemester_IdAndDeletedAtIsNullOrderByClassCodeAsc(semesterId);
	}

	@Override
	public AcademicClass saveClass(AcademicClass academicClass) {
		return classes.save(academicClass);
	}

	@Override
	public Optional<Course> findCourse(UUID id) {
		return courses.findById(id).filter(row -> row.getDeletedAt() == null);
	}

	@Override
	public boolean courseOfferingTaken(UUID academicClassId, UUID subjectId) {
		return courses.existsByAcademicClass_IdAndSubject_Id(academicClassId, subjectId);
	}

	@Override
	public List<Course> listCourses(UUID semesterId, UUID academicClassId, UUID subjectId, UUID lecturerId) {
		return courses.search(semesterId, academicClassId, subjectId, lecturerId);
	}

	@Override
	public Course saveCourse(Course course) {
		return courses.save(course);
	}

	@Override
	public boolean courseHasEnrollments(UUID courseId) {
		return enrollments.existsByCourse_Id(courseId);
	}

	@Override
	public boolean courseHasProjects(UUID courseId) {
		return projects.existsByCourse_Id(courseId);
	}

	@Override
	public Optional<Subject> findSubject(UUID id) {
		return subjects.findById(id);
	}

	@Override
	public Optional<SubjectSyllabusVersion> findSyllabus(UUID id) {
		return syllabi.findById(id);
	}

	@Override
	public Optional<LecturerProfile> findLecturer(UUID id) {
		return lecturers.findById(id);
	}
}
