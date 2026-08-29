package com.saga.be.service.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.saga.be.dto.academic.AcademicClassResponse;
import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.dto.academic.CreateAcademicClassRequest;
import com.saga.be.dto.academic.CreateCourseRequest;
import com.saga.be.dto.academic.CreateSemesterRequest;
import com.saga.be.dto.academic.PatchCourseRequest;
import com.saga.be.dto.academic.SemesterResponse;
import com.saga.be.dto.academic.SetActiveSemesterRequest;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AcademicRuntimeServiceTest {

	@Mock
	private AuditService audit;

	private InMemoryAcademicRuntimeStore store;
	private AcademicRuntimeService service;
	private UserAccount admin;

	@BeforeEach
	void setUp() {
		store = new InMemoryAcademicRuntimeStore();
		service = new AcademicRuntimeService(store, audit);
		admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
	}

	@Test
	void createSemesterNormalizesCode() {
		SemesterResponse created = service.createSemester(fa26(), admin, auditReq());
		assertEquals("FA26", created.code());
		assertEquals("Fall 2026", created.name());
		assertEquals(LocalDate.of(2026, 9, 1), created.startDate());
		assertEquals(LocalDate.of(2026, 12, 31), created.endDate());
		assertNotNull(created.id());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicRuntimeService.SEMESTER_CREATED),
						eq("semester"),
						eq(created.id()),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void duplicateSemesterCodeIsRejected() {
		service.createSemester(fa26(), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createSemester(
						new CreateSemesterRequest("fa26", "Other", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 30)),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.SEMESTER_CODE_DUPLICATE, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
		assertEquals(1, store.semesters.size());
	}

	@Test
	void invalidSemesterDateRangeIsRejected() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createSemester(
						new CreateSemesterRequest("SP27", "Spring", LocalDate.of(2027, 4, 30), LocalDate.of(2027, 1, 1)),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.SEMESTER_DATE_RANGE_INVALID, ex.getCode());
		assertTrue(store.semesters.isEmpty());
	}

	@Test
	void setActiveSemester() {
		UUID semesterId = service.createSemester(fa26(), admin, auditReq()).id();
		SemesterResponse active = service.setActiveSemester(new SetActiveSemesterRequest(semesterId), admin, auditReq());
		assertTrue(active.active());
		assertEquals(semesterId, service.getActiveSemester().id());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicRuntimeService.ACTIVE_SEMESTER_CHANGED),
						eq("active_semester_setting"),
						eq(semesterId),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void activeSemesterMustExist() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.setActiveSemester(new SetActiveSemesterRequest(UUID.randomUUID()), admin, auditReq()));
		assertEquals(AcademicErrorCode.SEMESTER_NOT_FOUND, ex.getCode());
		assertNull(service.getActiveSemester());
	}

	@Test
	void createClassInSemester() {
		UUID semesterId = service.createSemester(fa26(), admin, auditReq()).id();
		AcademicClassResponse created = service.createClass(
				new CreateAcademicClassRequest(semesterId, " se1705 ", "SE1705"), admin, auditReq());
		assertEquals("SE1705", created.classCode());
		assertEquals(semesterId, created.semesterId());
		assertEquals("FA26", created.semesterCode());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicRuntimeService.ACADEMIC_CLASS_CREATED),
						eq("academic_class"),
						eq(created.id()),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void classInNonexistentSemesterIsRejected() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createClass(
						new CreateAcademicClassRequest(UUID.randomUUID(), "SE1705", null), admin, auditReq()));
		assertEquals(AcademicErrorCode.SEMESTER_NOT_FOUND, ex.getCode());
	}

	@Test
	void duplicateClassCodeInSameSemesterIsRejected() {
		UUID semesterId = service.createSemester(fa26(), admin, auditReq()).id();
		service.createClass(new CreateAcademicClassRequest(semesterId, "SE1705", null), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createClass(new CreateAcademicClassRequest(semesterId, "se1705", "Other"), admin, auditReq()));
		assertEquals(AcademicErrorCode.ACADEMIC_CLASS_CODE_DUPLICATE, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
	}

	@Test
	void sameClassCodeAllowedInDifferentSemester() {
		UUID fa26 = service.createSemester(fa26(), admin, auditReq()).id();
		UUID sp27 = service.createSemester(
						new CreateSemesterRequest("SP27", "Spring 2027", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 30)),
						admin,
						auditReq())
				.id();
		service.createClass(new CreateAcademicClassRequest(fa26, "SE1705", null), admin, auditReq());
		AcademicClassResponse later = service.createClass(new CreateAcademicClassRequest(sp27, "SE1705", null), admin, auditReq());
		assertEquals("SE1705", later.classCode());
		assertEquals(sp27, later.semesterId());
		assertEquals(2, store.classes.size());
	}

	@Test
	void createValidCourse() {
		Fixture fx = fixture();
		CourseResponse created = service.createCourse(
				new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
				admin,
				auditReq());
		assertEquals(fx.classId, created.academicClassId());
		assertEquals("SE1705", created.classCode());
		assertEquals(fx.semesterId, created.semesterId());
		assertEquals("FA26", created.semesterCode());
		assertEquals(fx.subjectId, created.subjectId());
		assertEquals("SWP391", created.subjectCode());
		assertEquals(fx.syllabusId, created.syllabusVersionId());
		assertEquals("2026-v1", created.syllabusVersionLabel());
		assertEquals(SyllabusStatus.PUBLISHED, created.syllabusStatus());
		assertEquals(fx.lecturerId, created.lecturerId());
		assertEquals("lecturer@saga.local", created.lecturerEmail());
		assertEquals("SWP391 · SE1705", created.name());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicRuntimeService.COURSE_CREATED),
						eq("course"),
						eq(created.id()),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void courseWithNonexistentClassIsRejected() {
		Fixture fx = fixture();
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createCourse(
						new CreateCourseRequest(UUID.randomUUID(), fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.ACADEMIC_CLASS_NOT_FOUND, ex.getCode());
	}

	@Test
	void inactiveSubjectIsRejected() {
		Fixture fx = fixture();
		store.subjects.get(fx.subjectId).setStatus(SubjectStatus.INACTIVE);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.SUBJECT_STATUS_INVALID, ex.getCode());
	}

	@Test
	void draftSyllabusIsRejected() {
		Fixture fx = fixture();
		store.syllabi.get(fx.syllabusId).setStatus(SyllabusStatus.DRAFT);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.COURSE_SYLLABUS_NOT_PUBLISHED, ex.getCode());
	}

	@Test
	void archivedSyllabusIsRejectedForNewCourse() {
		Fixture fx = fixture();
		store.syllabi.get(fx.syllabusId).setStatus(SyllabusStatus.ARCHIVED);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.COURSE_SYLLABUS_ARCHIVED, ex.getCode());
	}

	@Test
	void syllabusFromAnotherSubjectIsRejected() {
		Fixture fx = fixture();
		Subject other = subject("SWT301", SubjectStatus.ACTIVE);
		SubjectSyllabusVersion otherSyllabus = syllabus(other, "2026-v1", SyllabusStatus.PUBLISHED);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, otherSyllabus.getId(), fx.lecturerId, null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.COURSE_SYLLABUS_SUBJECT_MISMATCH, ex.getCode());
	}

	@Test
	void invalidLecturerIsRejected() {
		Fixture fx = fixture();
		LecturerProfile student = lecturer(AccountRole.STUDENT, AccountStatus.ACTIVE);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, student.getId(), null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.COURSE_LECTURER_INVALID, ex.getCode());
	}

	@Test
	void duplicateCourseOfferingIsRejected() {
		Fixture fx = fixture();
		CreateCourseRequest request =
				new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null);
		service.createCourse(request, admin, auditReq());
		AcademicException ex = assertThrows(AcademicException.class, () -> service.createCourse(request, admin, auditReq()));
		assertEquals(AcademicErrorCode.COURSE_DUPLICATE, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
	}

	@Test
	void getCourseReturnsBoundContext() {
		Fixture fx = fixture();
		UUID courseId = service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, "SWP391-SE1705", "Custom"),
						admin,
						auditReq())
				.id();
		CourseResponse detail = service.getCourse(courseId);
		assertEquals("SWP391-SE1705", detail.courseCode());
		assertEquals("Custom", detail.name());
		assertEquals("SE1705", detail.classCode());
		assertEquals("FA26", detail.semesterCode());
		assertEquals("SWP391", detail.subjectCode());
		assertEquals("Software Development Project", detail.subjectName());
		assertEquals("2026-v1", detail.syllabusVersionLabel());
		assertEquals("lecturer@saga.local", detail.lecturerEmail());
	}

	@Test
	void lecturerReassignmentIsValidated() {
		Fixture fx = fixture();
		UUID courseId = service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
						admin,
						auditReq())
				.id();
		LecturerProfile next = lecturer(AccountRole.LECTURER, AccountStatus.ACTIVE);
		CourseResponse updated =
				service.updateCourse(courseId, new PatchCourseRequest(next.getId(), null, null, null), admin, auditReq());
		assertEquals(next.getId(), updated.lecturerId());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicRuntimeService.COURSE_LECTURER_CHANGED),
						eq("course"),
						eq(courseId),
						any(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
		LecturerProfile inactive = lecturer(AccountRole.LECTURER, AccountStatus.INACTIVE);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.updateCourse(
						courseId, new PatchCourseRequest(inactive.getId(), null, null, null), admin, auditReq()));
		assertEquals(AcademicErrorCode.COURSE_LECTURER_INVALID, ex.getCode());
	}

	@Test
	void syllabusChangeAllowedOnlyWithoutDownstreamData() {
		Fixture fx = fixture();
		UUID courseId = service.createCourse(
						new CreateCourseRequest(fx.classId, fx.subjectId, fx.syllabusId, fx.lecturerId, null, null),
						admin,
						auditReq())
				.id();
		SubjectSyllabusVersion next = syllabus(store.subjects.get(fx.subjectId), "2026-v2", SyllabusStatus.PUBLISHED);
		CourseResponse updated =
				service.updateCourse(courseId, new PatchCourseRequest(null, next.getId(), null, null), admin, auditReq());
		assertEquals(next.getId(), updated.syllabusVersionId());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicRuntimeService.COURSE_SYLLABUS_CHANGED),
						eq("course"),
						eq(courseId),
						any(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
		store.enrollmentCourseIds.add(courseId);
		SubjectSyllabusVersion third = syllabus(store.subjects.get(fx.subjectId), "2026-v3", SyllabusStatus.PUBLISHED);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.updateCourse(
						courseId, new PatchCourseRequest(null, third.getId(), null, null), admin, auditReq()));
		assertEquals(AcademicErrorCode.COURSE_SYLLABUS_IMMUTABLE, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
	}

	private Fixture fixture() {
		UUID semesterId = service.createSemester(fa26(), admin, auditReq()).id();
		UUID classId = service.createClass(new CreateAcademicClassRequest(semesterId, "SE1705", "SE1705"), admin, auditReq())
				.id();
		Subject subject = subject("SWP391", SubjectStatus.ACTIVE);
		subject.setName("Software Development Project");
		SubjectSyllabusVersion syllabus = syllabus(subject, "2026-v1", SyllabusStatus.PUBLISHED);
		LecturerProfile lecturer = lecturer(AccountRole.LECTURER, AccountStatus.ACTIVE);
		return new Fixture(semesterId, classId, subject.getId(), syllabus.getId(), lecturer.getId());
	}

	private Subject subject(String code, SubjectStatus status) {
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode(code);
		subject.setName(code);
		subject.setStatus(status);
		store.subjects.put(subject.getId(), subject);
		return subject;
	}

	private SubjectSyllabusVersion syllabus(Subject subject, String label, SyllabusStatus status) {
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setId(UUID.randomUUID());
		syllabus.setSubject(subject);
		syllabus.setVersionLabel(label);
		syllabus.setStatus(status);
		store.syllabi.put(syllabus.getId(), syllabus);
		return syllabus;
	}

	private LecturerProfile lecturer(AccountRole role, AccountStatus status) {
		UserAccount account = user(role, status);
		account.setEmail(role == AccountRole.LECTURER && status == AccountStatus.ACTIVE
				? "lecturer@saga.local"
				: UUID.randomUUID() + "@saga.local");
		LecturerProfile profile = new LecturerProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		store.lecturers.put(profile.getId(), profile);
		return profile;
	}

	private static UserAccount user(AccountRole role, AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("admin@saga.local");
		account.setAccountRole(role);
		account.setAccountStatus(status);
		return account;
	}

	private static CreateSemesterRequest fa26() {
		return new CreateSemesterRequest(" fa26 ", "Fall 2026", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-1", "127.0.0.1", "test");
	}

	private record Fixture(UUID semesterId, UUID classId, UUID subjectId, UUID syllabusId, UUID lecturerId) {}
}
