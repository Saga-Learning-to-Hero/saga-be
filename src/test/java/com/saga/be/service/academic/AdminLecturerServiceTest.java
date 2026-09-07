package com.saga.be.service.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.dto.academic.CreateAcademicClassRequest;
import com.saga.be.dto.academic.CreateCourseRequest;
import com.saga.be.dto.academic.CreateSemesterRequest;
import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminLecturerServiceTest {

	@Mock
	private LecturerProfileRepository lecturers;
	@Mock
	private AuditService audit;

	private AdminLecturerService directory;
	private InMemoryAcademicRuntimeStore store;
	private AcademicRuntimeService runtime;
	private UserAccount admin;

	@BeforeEach
	void setUp() {
		directory = new AdminLecturerService(lecturers);
		store = new InMemoryAcademicRuntimeStore();
		runtime = new AcademicRuntimeService(store, audit);
		admin = account(AccountRole.ADMIN, AccountStatus.ACTIVE, "admin@saga.local", "Admin");
	}

	@Test
	void defaultListReturnsAssignableLecturerProfileIdNotUserId() {
		LecturerProfile active = lecturer("Lan", "lan@fe.edu.vn", AccountRole.LECTURER, AccountStatus.ACTIVE);
		when(lecturers.searchDirectory(eq(true), eq(AccountRole.LECTURER), eq(AccountStatus.ACTIVE), isNull()))
				.thenReturn(List.of(active));
		List<LecturerDirectoryResponse> rows = directory.list(null, null);
		assertEquals(1, rows.size());
		assertEquals(active.getId(), rows.getFirst().lecturerProfileId());
		assertEquals(active.getUserAccount().getId(), rows.getFirst().userId());
		assertNotEquals(rows.getFirst().lecturerProfileId(), rows.getFirst().userId());
		assertEquals("Lan", rows.getFirst().fullName());
		assertEquals("lan@fe.edu.vn", rows.getFirst().email());
		assertTrue(rows.getFirst().active());
	}

	@Test
	void defaultListExcludesInactiveLecturers() {
		when(lecturers.searchDirectory(eq(true), eq(AccountRole.LECTURER), eq(AccountStatus.ACTIVE), isNull()))
				.thenReturn(List.of());
		assertTrue(directory.list(null, null).isEmpty());
		verify(lecturers).searchDirectory(true, AccountRole.LECTURER, AccountStatus.ACTIVE, null);
	}

	@Test
	void activeFalseListsNonAssignableProfilesWithStateFlag() {
		LecturerProfile inactive = lecturer("Off", "off@fe.edu.vn", AccountRole.LECTURER, AccountStatus.INACTIVE);
		when(lecturers.searchDirectory(eq(false), eq(AccountRole.LECTURER), eq(AccountStatus.ACTIVE), isNull()))
				.thenReturn(List.of(inactive));
		List<LecturerDirectoryResponse> rows = directory.list(false, null);
		assertEquals(1, rows.size());
		assertFalse(rows.getFirst().active());
		assertEquals(inactive.getId(), rows.getFirst().lecturerProfileId());
	}

	@Test
	void searchIsPassedToRepository() {
		when(lecturers.searchDirectory(eq(true), eq(AccountRole.LECTURER), eq(AccountStatus.ACTIVE), eq("lan")))
				.thenReturn(List.of());
		assertTrue(directory.list(true, " lan ").isEmpty());
		verify(lecturers).searchDirectory(true, AccountRole.LECTURER, AccountStatus.ACTIVE, "lan");
	}

	@Test
	void emptyDirectoryIsEmptyList() {
		when(lecturers.searchDirectory(eq(true), eq(AccountRole.LECTURER), eq(AccountStatus.ACTIVE), isNull()))
				.thenReturn(List.of());
		assertEquals(List.of(), directory.list(null, null));
	}

	@Test
	void listedLecturerProfileIdIsAcceptedByCourseCreateAndUserIdIsRejected() {
		LecturerProfile lecturer = lecturer("Lan", "lan@fe.edu.vn", AccountRole.LECTURER, AccountStatus.ACTIVE);
		store.lecturers.put(lecturer.getId(), lecturer);
		when(lecturers.searchDirectory(eq(true), eq(AccountRole.LECTURER), eq(AccountStatus.ACTIVE), isNull()))
				.thenReturn(List.of(lecturer));
		LecturerDirectoryResponse listed = directory.list(null, null).getFirst();
		assertNotEquals(listed.lecturerProfileId(), listed.userId());

		UUID semesterId = runtime.createSemester(
						new CreateSemesterRequest("FA26", "Fall 2026", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31)),
						admin,
						auditReq())
				.id();
		UUID classId = runtime.createClass(new CreateAcademicClassRequest(semesterId, "SE1705", "SE1705"), admin, auditReq())
				.id();
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode("SWP391");
		subject.setName("Software Development Project");
		subject.setStatus(SubjectStatus.ACTIVE);
		store.subjects.put(subject.getId(), subject);
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setId(UUID.randomUUID());
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("2026-v1");
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		store.syllabi.put(syllabus.getId(), syllabus);

		CourseResponse created = runtime.createCourse(
				new CreateCourseRequest(classId, subject.getId(), syllabus.getId(), listed.lecturerProfileId(), null, null),
				admin,
				auditReq());
		assertEquals(listed.lecturerProfileId(), created.lecturerId());
		assertEquals(listed.userId(), created.lecturerUserId());

		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> runtime.createCourse(
						new CreateCourseRequest(classId, subject.getId(), syllabus.getId(), listed.userId(), null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.COURSE_LECTURER_INVALID, ex.getCode());
	}

	private static LecturerProfile lecturer(String name, String email, AccountRole role, AccountStatus status) {
		UserAccount account = account(role, status, email, name);
		LecturerProfile profile = new LecturerProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		return profile;
	}

	private static UserAccount account(AccountRole role, AccountStatus status, String email, String name) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setFullName(name);
		account.setAccountRole(role);
		account.setAccountStatus(status);
		return account;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-1", "127.0.0.1", "test");
	}
}
