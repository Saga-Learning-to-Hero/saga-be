package com.saga.be.service.academic;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.account.LecturerProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AcademicRuntimeStore {

	Optional<Semester> findSemester(UUID id);

	Optional<Semester> findSemesterByCode(String code);

	boolean semesterCodeTaken(String code, UUID excludeId);

	boolean semesterHasClasses(UUID semesterId);

	boolean semesterHasCourses(UUID semesterId);

	List<Semester> listSemesters();

	Semester saveSemester(Semester semester);

	ActiveSemesterSetting getActiveSemesterSetting();

	ActiveSemesterSetting saveActiveSemesterSetting(ActiveSemesterSetting setting);

	Optional<AcademicClass> findClass(UUID id);

	boolean classCodeTaken(UUID semesterId, String classCode, UUID excludeId);

	boolean classHasCourses(UUID classId);

	List<AcademicClass> listClasses(UUID semesterId);

	AcademicClass saveClass(AcademicClass academicClass);

	Optional<Course> findCourse(UUID id);

	boolean courseOfferingTaken(UUID academicClassId, UUID subjectId);

	Page<UUID> listCoursePageIds(
			UUID semesterId, UUID academicClassId, UUID subjectId, UUID lecturerId, Pageable pageable);

	List<Course> findCoursesFetchedByIdIn(Collection<UUID> ids);

	Course saveCourse(Course course);

	boolean courseHasEnrollments(UUID courseId);

	boolean courseHasProjects(UUID courseId);

	Optional<Subject> findSubject(UUID id);

	Optional<SubjectSyllabusVersion> findSyllabus(UUID id);

	Optional<LecturerProfile> findLecturer(UUID id);
}
