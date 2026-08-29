package com.saga.be.service.academic;

import com.saga.be.dto.academic.AcademicClassResponse;
import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.dto.academic.CreateAcademicClassRequest;
import com.saga.be.dto.academic.CreateCourseRequest;
import com.saga.be.dto.academic.CreateSemesterRequest;
import com.saga.be.dto.academic.PatchAcademicClassRequest;
import com.saga.be.dto.academic.PatchCourseRequest;
import com.saga.be.dto.academic.PatchSemesterRequest;
import com.saga.be.dto.academic.SemesterResponse;
import com.saga.be.dto.academic.SetActiveSemesterRequest;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class AcademicRuntimeService {

	public static final String SEMESTER_CREATED = "SEMESTER_CREATED";
	public static final String SEMESTER_UPDATED = "SEMESTER_UPDATED";
	public static final String ACTIVE_SEMESTER_CHANGED = "ACTIVE_SEMESTER_CHANGED";
	public static final String ACADEMIC_CLASS_CREATED = "ACADEMIC_CLASS_CREATED";
	public static final String ACADEMIC_CLASS_UPDATED = "ACADEMIC_CLASS_UPDATED";
	public static final String COURSE_CREATED = "COURSE_CREATED";
	public static final String COURSE_UPDATED = "COURSE_UPDATED";
	public static final String COURSE_LECTURER_CHANGED = "COURSE_LECTURER_CHANGED";
	public static final String COURSE_SYLLABUS_CHANGED = "COURSE_SYLLABUS_CHANGED";

	private final AcademicRuntimeStore store;
	private final AuditService audit;

	public AcademicRuntimeService(AcademicRuntimeStore store, AuditService audit) {
		this.store = store;
		this.audit = audit;
	}

	@Transactional
	public SemesterResponse createSemester(CreateSemesterRequest request, UserAccount actor, AuditRequest auditRequest) {
		String code = normalizeCode(request.code(), AcademicErrorCode.SEMESTER_CODE_INVALID, "Semester code is required.");
		String name = requireName(request.name());
		validateDateRange(request.startDate(), request.endDate());
		if (store.semesterCodeTaken(code, null)) {
			throw conflict(AcademicErrorCode.SEMESTER_CODE_DUPLICATE, "Semester code already exists.");
		}
		Semester semester = new Semester();
		semester.setCode(code);
		semester.setName(name);
		semester.setStartDate(startOfDay(request.startDate()));
		semester.setEndDate(startOfDay(request.endDate()));
		Semester saved = store.saveSemester(semester);
		audit(actor, SEMESTER_CREATED, "semester", saved.getId(), null, semesterSnapshot(saved), Map.of("code", saved.getCode()), auditRequest);
		return toSemester(saved, false);
	}

	@Transactional(readOnly = true)
	public List<SemesterResponse> listSemesters() {
		UUID activeId = activeSemesterId();
		return store.listSemesters().stream().map(semester -> toSemester(semester, semester.getId().equals(activeId))).toList();
	}

	@Transactional(readOnly = true)
	public SemesterResponse getSemester(UUID semesterId) {
		Semester semester = requireSemester(semesterId);
		return toSemester(semester, semester.getId().equals(activeSemesterId()));
	}

	@Transactional
	public SemesterResponse updateSemester(
			UUID semesterId, PatchSemesterRequest request, UserAccount actor, AuditRequest auditRequest) {
		Semester semester = requireSemester(semesterId);
		Map<String, Object> before = semesterSnapshot(semester);
		boolean changed = false;
		if (request.code() != null) {
			String code = normalizeCode(request.code(), AcademicErrorCode.SEMESTER_CODE_INVALID, "Semester code is required.");
			if (!code.equals(semester.getCode())) {
				if (store.semesterCodeTaken(code, semester.getId())) {
					throw conflict(AcademicErrorCode.SEMESTER_CODE_DUPLICATE, "Semester code already exists.");
				}
				semester.setCode(code);
				changed = true;
			}
		}
		if (request.name() != null) {
			semester.setName(requireName(request.name()));
			changed = true;
		}
		LocalDate start = request.startDate() != null ? request.startDate() : toDate(semester.getStartDate());
		LocalDate end = request.endDate() != null ? request.endDate() : toDate(semester.getEndDate());
		if (request.startDate() != null || request.endDate() != null) {
			validateDateRange(start, end);
			semester.setStartDate(startOfDay(start));
			semester.setEndDate(startOfDay(end));
			changed = true;
		}
		if (changed) {
			store.saveSemester(semester);
			audit(actor, SEMESTER_UPDATED, "semester", semester.getId(), before, semesterSnapshot(semester), Map.of("code", semester.getCode()), auditRequest);
		}
		return toSemester(semester, semester.getId().equals(activeSemesterId()));
	}

	@Transactional(readOnly = true)
	public SemesterResponse getActiveSemester() {
		Semester semester = store.getActiveSemesterSetting().getSemester();
		if (semester == null || semester.getDeletedAt() != null) {
			return null;
		}
		return toSemester(semester, true);
	}

	@Transactional
	public SemesterResponse setActiveSemester(
			SetActiveSemesterRequest request, UserAccount actor, AuditRequest auditRequest) {
		Semester semester = requireSemester(request.semesterId());
		ActiveSemesterSetting setting = store.getActiveSemesterSetting();
		UUID previousId = setting.getSemester() == null ? null : setting.getSemester().getId();
		setting.setSemester(semester);
		setting.setUpdatedBy(actor);
		setting.setUpdatedAt(LocalDateTime.now());
		store.saveActiveSemesterSetting(setting);
		audit(
				actor,
				ACTIVE_SEMESTER_CHANGED,
				"active_semester_setting",
				semester.getId(),
				previousId == null ? null : Map.of("semesterId", previousId.toString()),
				Map.of("semesterId", semester.getId().toString(), "code", semester.getCode()),
				Map.of("code", semester.getCode()),
				auditRequest);
		return toSemester(semester, true);
	}

	@Transactional
	public AcademicClassResponse createClass(
			CreateAcademicClassRequest request, UserAccount actor, AuditRequest auditRequest) {
		Semester semester = requireSemester(request.semesterId());
		String classCode = normalizeCode(request.classCode(), AcademicErrorCode.ACADEMIC_CLASS_CODE_INVALID, "Class code is required.");
		if (store.classCodeTaken(semester.getId(), classCode, null)) {
			throw conflict(AcademicErrorCode.ACADEMIC_CLASS_CODE_DUPLICATE, "Class code already exists in this semester.");
		}
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode(classCode);
		academicClass.setName(StringUtils.hasText(request.name()) ? request.name().trim() : classCode);
		AcademicClass saved = store.saveClass(academicClass);
		audit(
				actor,
				ACADEMIC_CLASS_CREATED,
				"academic_class",
				saved.getId(),
				null,
				classSnapshot(saved),
				Map.of("classCode", saved.getClassCode(), "semesterId", semester.getId().toString()),
				auditRequest);
		return toClass(saved);
	}

	@Transactional(readOnly = true)
	public List<AcademicClassResponse> listClasses(UUID semesterId) {
		if (semesterId != null) {
			requireSemester(semesterId);
		}
		return store.listClasses(semesterId).stream().map(this::toClass).toList();
	}

	@Transactional(readOnly = true)
	public AcademicClassResponse getAcademicClass(UUID classId) {
		return toClass(requireClass(classId));
	}

	@Transactional
	public AcademicClassResponse updateClass(
			UUID classId, PatchAcademicClassRequest request, UserAccount actor, AuditRequest auditRequest) {
		AcademicClass academicClass = requireClass(classId);
		if (academicClass.getSemester() == null) {
			throw badRequest(AcademicErrorCode.ACADEMIC_CLASS_SEMESTER_REQUIRED, "Academic class is not bound to a semester.");
		}
		Map<String, Object> before = classSnapshot(academicClass);
		boolean changed = false;
		if (request.classCode() != null) {
			String classCode =
					normalizeCode(request.classCode(), AcademicErrorCode.ACADEMIC_CLASS_CODE_INVALID, "Class code is required.");
			if (!classCode.equals(academicClass.getClassCode())) {
				if (store.classCodeTaken(academicClass.getSemester().getId(), classCode, academicClass.getId())) {
					throw conflict(AcademicErrorCode.ACADEMIC_CLASS_CODE_DUPLICATE, "Class code already exists in this semester.");
				}
				academicClass.setClassCode(classCode);
				changed = true;
			}
		}
		if (request.name() != null) {
			academicClass.setName(requireName(request.name()));
			changed = true;
		}
		if (changed) {
			store.saveClass(academicClass);
			audit(
					actor,
					ACADEMIC_CLASS_UPDATED,
					"academic_class",
					academicClass.getId(),
					before,
					classSnapshot(academicClass),
					Map.of("classCode", academicClass.getClassCode()),
					auditRequest);
		}
		return toClass(academicClass);
	}

	@Transactional
	public CourseResponse createCourse(CreateCourseRequest request, UserAccount actor, AuditRequest auditRequest) {
		AcademicClass academicClass = requireClass(request.academicClassId());
		if (academicClass.getSemester() == null) {
			throw badRequest(AcademicErrorCode.ACADEMIC_CLASS_SEMESTER_REQUIRED, "Academic class is not bound to a semester.");
		}
		Subject subject = requireActiveSubject(request.subjectId());
		SubjectSyllabusVersion syllabus = requirePublishedSyllabus(request.syllabusVersionId(), subject.getId());
		LecturerProfile lecturer = requireEligibleLecturer(request.lecturerId());
		if (store.courseOfferingTaken(academicClass.getId(), subject.getId())) {
			throw conflict(AcademicErrorCode.COURSE_DUPLICATE, "This class already has a course for that subject.");
		}
		Course course = new Course();
		course.setAcademicClass(academicClass);
		course.setSemester(academicClass.getSemester());
		course.setSubject(subject);
		course.setSyllabusVersion(syllabus);
		course.setInstructor(lecturer);
		course.setCourseCode(trimToNull(request.courseCode()));
		course.setName(StringUtils.hasText(request.name()) ? request.name().trim() : defaultCourseName(subject, academicClass));
		Course saved = store.saveCourse(course);
		audit(
				actor,
				COURSE_CREATED,
				"course",
				saved.getId(),
				null,
				courseSnapshot(saved),
				Map.of(
						"academicClassId", academicClass.getId().toString(),
						"subjectId", subject.getId().toString(),
						"syllabusVersionId", syllabus.getId().toString()),
				auditRequest);
		return toCourse(saved);
	}

	@Transactional(readOnly = true)
	public List<CourseResponse> listCourses(UUID semesterId, UUID academicClassId, UUID subjectId, UUID lecturerId) {
		if (semesterId != null) {
			requireSemester(semesterId);
		}
		if (academicClassId != null) {
			requireClass(academicClassId);
		}
		return store.listCourses(semesterId, academicClassId, subjectId, lecturerId).stream().map(this::toCourse).toList();
	}

	@Transactional(readOnly = true)
	public CourseResponse getCourse(UUID courseId) {
		return toCourse(requireCourse(courseId));
	}

	@Transactional
	public CourseResponse updateCourse(UUID courseId, PatchCourseRequest request, UserAccount actor, AuditRequest auditRequest) {
		Course course = requireCourse(courseId);
		Map<String, Object> before = courseSnapshot(course);
		boolean changed = false;
		if (request.name() != null) {
			course.setName(requireName(request.name()));
			changed = true;
		}
		if (request.courseCode() != null) {
			course.setCourseCode(trimToNull(request.courseCode()));
			changed = true;
		}
		UUID previousLecturerId = course.getInstructor() == null ? null : course.getInstructor().getId();
		if (request.lecturerId() != null
				&& (previousLecturerId == null || !request.lecturerId().equals(previousLecturerId))) {
			LecturerProfile lecturer = requireEligibleLecturer(request.lecturerId());
			course.setInstructor(lecturer);
			changed = true;
			audit(
					actor,
					COURSE_LECTURER_CHANGED,
					"course",
					course.getId(),
					previousLecturerId == null ? null : Map.of("lecturerId", previousLecturerId.toString()),
					Map.of("lecturerId", lecturer.getId().toString()),
					Map.of(),
					auditRequest);
		}
		UUID previousSyllabusId = course.getSyllabusVersionId();
		if (request.syllabusVersionId() != null && !request.syllabusVersionId().equals(previousSyllabusId)) {
			if (store.courseHasEnrollments(course.getId()) || store.courseHasProjects(course.getId())) {
				throw conflict(
						AcademicErrorCode.COURSE_SYLLABUS_IMMUTABLE,
						"Syllabus version cannot change after enrollments or projects exist.");
			}
			SubjectSyllabusVersion syllabus =
					requirePublishedSyllabus(request.syllabusVersionId(), course.getSubject().getId());
			course.setSyllabusVersion(syllabus);
			changed = true;
			audit(
					actor,
					COURSE_SYLLABUS_CHANGED,
					"course",
					course.getId(),
					previousSyllabusId == null ? null : Map.of("syllabusVersionId", previousSyllabusId.toString()),
					Map.of("syllabusVersionId", syllabus.getId().toString(), "versionLabel", syllabus.getVersionLabel()),
					Map.of(),
					auditRequest);
		}
		if (changed) {
			store.saveCourse(course);
			audit(actor, COURSE_UPDATED, "course", course.getId(), before, courseSnapshot(course), Map.of(), auditRequest);
		}
		return toCourse(course);
	}

	private Semester requireSemester(UUID semesterId) {
		return store.findSemester(semesterId)
				.orElseThrow(() -> notFound(AcademicErrorCode.SEMESTER_NOT_FOUND, "Semester was not found."));
	}

	private AcademicClass requireClass(UUID classId) {
		return store.findClass(classId)
				.orElseThrow(() -> notFound(AcademicErrorCode.ACADEMIC_CLASS_NOT_FOUND, "Academic class was not found."));
	}

	private Course requireCourse(UUID courseId) {
		return store.findCourse(courseId).orElseThrow(() -> notFound(AcademicErrorCode.COURSE_NOT_FOUND, "Course was not found."));
	}

	private Subject requireActiveSubject(UUID subjectId) {
		Subject subject = store.findSubject(subjectId)
				.filter(row -> row.getDeletedAt() == null)
				.orElseThrow(() -> notFound(AcademicErrorCode.SUBJECT_NOT_FOUND, "Subject was not found."));
		if (subject.getStatus() != SubjectStatus.ACTIVE) {
			throw badRequest(AcademicErrorCode.SUBJECT_STATUS_INVALID, "Subject must be ACTIVE to create a course.");
		}
		return subject;
	}

	private SubjectSyllabusVersion requirePublishedSyllabus(UUID syllabusId, UUID subjectId) {
		SubjectSyllabusVersion syllabus = store.findSyllabus(syllabusId)
				.orElseThrow(() -> notFound(AcademicErrorCode.SYLLABUS_NOT_FOUND, "Syllabus version was not found."));
		UUID syllabusSubjectId = syllabus.getSubjectId() != null
				? syllabus.getSubjectId()
				: syllabus.getSubject() == null ? null : syllabus.getSubject().getId();
		if (syllabusSubjectId == null || !syllabusSubjectId.equals(subjectId)) {
			throw badRequest(
					AcademicErrorCode.COURSE_SYLLABUS_SUBJECT_MISMATCH, "Syllabus version does not belong to the course subject.");
		}
		if (syllabus.getStatus() == SyllabusStatus.DRAFT) {
			throw badRequest(AcademicErrorCode.COURSE_SYLLABUS_NOT_PUBLISHED, "Course can only pin a PUBLISHED syllabus version.");
		}
		if (syllabus.getStatus() == SyllabusStatus.ARCHIVED) {
			throw badRequest(AcademicErrorCode.COURSE_SYLLABUS_ARCHIVED, "Course cannot pin an ARCHIVED syllabus version.");
		}
		if (syllabus.getStatus() != SyllabusStatus.PUBLISHED) {
			throw badRequest(AcademicErrorCode.COURSE_SYLLABUS_NOT_PUBLISHED, "Course can only pin a PUBLISHED syllabus version.");
		}
		return syllabus;
	}

	private LecturerProfile requireEligibleLecturer(UUID lecturerId) {
		LecturerProfile lecturer = store.findLecturer(lecturerId)
				.orElseThrow(() -> badRequest(AcademicErrorCode.COURSE_LECTURER_INVALID, "Lecturer was not found."));
		UserAccount user = lecturer.getUserAccount();
		if (user == null
				|| user.getAccountRole() != AccountRole.LECTURER
				|| user.getAccountStatus() != AccountStatus.ACTIVE) {
			throw badRequest(
					AcademicErrorCode.COURSE_LECTURER_INVALID, "Lecturer must be an active lecturer account.");
		}
		return lecturer;
	}

	private UUID activeSemesterId() {
		Semester active = store.getActiveSemesterSetting().getSemester();
		return active == null ? null : active.getId();
	}

	private SemesterResponse toSemester(Semester semester, boolean active) {
		return new SemesterResponse(
				semester.getId(),
				semester.getCode(),
				semester.getName(),
				toDate(semester.getStartDate()),
				toDate(semester.getEndDate()),
				active,
				semester.getCreatedAt(),
				semester.getUpdatedAt());
	}

	private AcademicClassResponse toClass(AcademicClass academicClass) {
		Semester semester = academicClass.getSemester();
		return new AcademicClassResponse(
				academicClass.getId(),
				semester == null ? null : semester.getId(),
				semester == null ? null : semester.getCode(),
				academicClass.getClassCode(),
				academicClass.getName(),
				academicClass.getCreatedAt(),
				academicClass.getUpdatedAt());
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

	private Map<String, Object> semesterSnapshot(Semester semester) {
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("code", semester.getCode());
		snap.put("name", semester.getName());
		snap.put("startDate", toDate(semester.getStartDate()));
		snap.put("endDate", toDate(semester.getEndDate()));
		return snap;
	}

	private Map<String, Object> classSnapshot(AcademicClass academicClass) {
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("classCode", academicClass.getClassCode());
		snap.put("name", academicClass.getName());
		if (academicClass.getSemester() != null) {
			snap.put("semesterId", academicClass.getSemester().getId().toString());
		}
		return snap;
	}

	private Map<String, Object> courseSnapshot(Course course) {
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("name", course.getName());
		snap.put("courseCode", course.getCourseCode());
		if (course.getSubject() != null) {
			snap.put("subjectId", course.getSubject().getId().toString());
		}
		if (course.getSyllabusVersionId() != null) {
			snap.put("syllabusVersionId", course.getSyllabusVersionId().toString());
		}
		if (course.getInstructor() != null) {
			snap.put("lecturerId", course.getInstructor().getId().toString());
		}
		return snap;
	}

	private void audit(
			UserAccount actor,
			String action,
			String entityType,
			UUID entityId,
			Map<String, Object> before,
			Map<String, Object> after,
			Map<String, Object> metadata,
			AuditRequest auditRequest) {
		if (audit == null) {
			return;
		}
		String requestId = auditRequest == null ? null : auditRequest.requestId();
		String ip = auditRequest == null ? null : auditRequest.ip();
		String userAgent = auditRequest == null ? null : auditRequest.userAgent();
		audit.record(actor, null, null, action, entityType, entityId, before, after, metadata, AuditSource.API, requestId, ip, userAgent);
	}

	private static String normalizeCode(String raw, AcademicErrorCode invalid, String message) {
		if (!StringUtils.hasText(raw)) {
			throw badRequest(invalid, message);
		}
		return raw.trim().toUpperCase();
	}

	private static String requireName(String name) {
		if (!StringUtils.hasText(name)) {
			throw badRequest(AcademicErrorCode.SEMESTER_CODE_INVALID, "Name is required.");
		}
		return name.trim();
	}

	private static void validateDateRange(LocalDate start, LocalDate end) {
		if (start == null || end == null || !start.isBefore(end)) {
			throw badRequest(AcademicErrorCode.SEMESTER_DATE_RANGE_INVALID, "startDate must be before endDate.");
		}
	}

	private static LocalDateTime startOfDay(LocalDate date) {
		return date.atStartOfDay();
	}

	private static LocalDate toDate(LocalDateTime value) {
		return value == null ? null : value.toLocalDate();
	}

	private static String trimToNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return value.trim();
	}

	private static String defaultCourseName(Subject subject, AcademicClass academicClass) {
		return subject.getSubjectCode() + " · " + academicClass.getClassCode();
	}

	private static AcademicException badRequest(AcademicErrorCode code, String message) {
		return new AcademicException(code, HttpStatus.BAD_REQUEST, message);
	}

	private static AcademicException notFound(AcademicErrorCode code, String message) {
		return new AcademicException(code, HttpStatus.NOT_FOUND, message);
	}

	private static AcademicException conflict(AcademicErrorCode code, String message) {
		return new AcademicException(code, HttpStatus.CONFLICT, message);
	}
}
