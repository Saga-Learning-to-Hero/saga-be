package com.saga.be.service.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.dto.academic.ActivityInput;
import com.saga.be.dto.academic.CreateSubjectRequest;
import com.saga.be.dto.academic.CreateSyllabusRequest;
import com.saga.be.dto.academic.DeliverableInput;
import com.saga.be.dto.academic.LearningOutcomeInput;
import com.saga.be.dto.academic.LearningUnitInput;
import com.saga.be.dto.academic.PatchSubjectRequest;
import com.saga.be.dto.academic.PatchSyllabusRequest;
import com.saga.be.dto.academic.PhaseInput;
import com.saga.be.dto.academic.SubjectResponse;
import com.saga.be.dto.academic.SyllabusDetailResponse;
import com.saga.be.dto.academic.SyllabusStructureRequest;
import com.saga.be.dto.academic.SyllabusSummaryResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.audit.AuditService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AcademicCatalogServiceTest {

	@Mock
	private AuditService audit;

	private InMemoryAcademicCatalogStore store;
	private AcademicCatalogService service;
	private UserAccount admin;

	@BeforeEach
	void setUp() {
		store = new InMemoryAcademicCatalogStore();
		service = new AcademicCatalogService(store, audit);
		admin = new UserAccount();
		admin.setId(UUID.randomUUID());
		admin.setEmail("admin@saga.local");
		admin.setAccountRole(AccountRole.ADMIN);
	}

	@Test
	void createSubjectPersistsNormalizedCode() {
		SubjectResponse created = service.createSubject(
				new CreateSubjectRequest(" swp391 ", "Software Development Project", "Dự án phát triển phần mềm"),
				admin,
				auditReq());
		assertEquals("SWP391", created.code());
		assertEquals("Software Development Project", created.nameEnglish());
		assertEquals(SubjectStatus.ACTIVE, created.status());
		assertNotNull(created.id());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicCatalogService.SUBJECT_CREATED),
						eq("subject"),
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
	void duplicateSubjectCodeIsRejected() {
		service.createSubject(new CreateSubjectRequest("SWP391", "A", null), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.createSubject(new CreateSubjectRequest("swp391", "B", null), admin, auditReq()));
		assertEquals(AcademicErrorCode.SUBJECT_CODE_DUPLICATE, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
		assertEquals(1, store.subjects.size());
	}

	@Test
	void createDraftSyllabusAndUpdateMetadata() {
		UUID subjectId = service.createSubject(new CreateSubjectRequest("SWT301", "Software Testing", null), admin, auditReq())
				.id();
		SyllabusSummaryResponse created = service.createSyllabus(
				subjectId,
				new CreateSyllabusRequest(
						"14177",
						"2026-v1",
						"Testing",
						null,
						BigDecimal.valueOf(3),
						"Bachelor",
						null,
						null,
						null,
						"desc",
						null,
						"Jira",
						"Sommerville",
						"IEEE 829",
						null),
				admin,
				auditReq());
		assertEquals(SyllabusStatus.DRAFT, created.status());
		assertEquals("2026-v1", created.versionLabel());
		assertEquals("Sommerville", store.syllabi.get(created.id()).getTextbooks());
		assertEquals("IEEE 829", store.syllabi.get(created.id()).getReferenceMaterials());
		SyllabusSummaryResponse updated = service.updateSyllabus(
				subjectId,
				created.id(),
				new PatchSyllabusRequest(
						null, "2026-v1-rev", null, null, null, null, null, null, null, "updated", null, null, null, null, null),
				admin,
				auditReq());
		assertEquals("2026-v1-rev", updated.versionLabel());
		assertEquals("updated", store.syllabi.get(created.id()).getDescription());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicCatalogService.SYLLABUS_CREATED),
						eq("subject_syllabus_version"),
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
	void replaceStructureIsTransactionalAndOrdered() {
		PreparedSyllabus prepared = draft("SWR302");
		SyllabusDetailResponse detail = service.replaceStructure(
				prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq());
		assertEquals(2, detail.learningOutcomes().size());
		assertEquals("LO1", detail.learningOutcomes().get(0).code());
		assertEquals("BLACK_BOX_TESTING", detail.learningUnits().get(0).code());
		assertEquals(List.of("LO2"), detail.learningUnits().get(0).learningOutcomeCodes());
		assertEquals("ELICITATION", detail.phases().get(0).code());
		assertEquals(List.of("LO1"), detail.phases().get(0).learningOutcomeCodes());
		assertEquals("REQUIREMENT_ANALYSIS", detail.phases().get(0).activities().get(0).code());
		assertEquals("REQUIREMENT_SPECIFICATION", detail.phases().get(0).deliverables().get(0).code());
		assertEquals(List.of("LO1"), detail.phases().get(0).deliverables().get(0).learningOutcomeCodes());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicCatalogService.SYLLABUS_STRUCTURE_UPDATED),
						eq("subject_syllabus_version"),
						eq(prepared.syllabusId),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void invalidOutcomeReferenceLeavesPreviousStructure() {
		PreparedSyllabus prepared = draft("SWP391");
		service.replaceStructure(prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.replaceStructure(
						prepared.subjectId,
						prepared.syllabusId,
						new SyllabusStructureRequest(
								List.of(new LearningOutcomeInput("LO1", "Analyze", null, 1)),
								List.of(),
								List.of(new PhaseInput(
										"REQUIREMENT",
										"Requirements",
										null,
										1,
										List.of("LO9"),
										List.of(),
										List.of()))),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.LEARNING_OUTCOME_REFERENCE_INVALID, ex.getCode());
		assertEquals(2, store.listOutcomes(prepared.syllabusId).size());
		assertEquals("ELICITATION", store.listPhases(prepared.syllabusId).get(0).getCode());
	}

	@Test
	void duplicatePhaseCodeIsRejected() {
		PreparedSyllabus prepared = draft("SWT301");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.replaceStructure(
						prepared.subjectId,
						prepared.syllabusId,
						new SyllabusStructureRequest(
								List.of(new LearningOutcomeInput("LO1", "A", null, 1)),
								List.of(),
								List.of(
										new PhaseInput("DESIGN", "Design", null, 1, List.of(), List.of(), List.of()),
										new PhaseInput("design", "Design again", null, 2, List.of(), List.of(), List.of()))),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.ACADEMIC_CODE_DUPLICATE, ex.getCode());
		assertTrue(store.listPhases(prepared.syllabusId).isEmpty());
	}

	@Test
	void publishValidSyllabusThenRejectEmptyAndMutation() {
		PreparedSyllabus prepared = draft("SWP391");
		service.replaceStructure(prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq());
		SyllabusDetailResponse published = service.publish(prepared.subjectId, prepared.syllabusId, admin, auditReq());
		assertEquals(SyllabusStatus.PUBLISHED, published.status());
		assertNotNull(published.publishedAt());
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicCatalogService.SYLLABUS_PUBLISHED),
						eq("subject_syllabus_version"),
						eq(prepared.syllabusId),
						any(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());

		AcademicException mutate = assertThrows(
				AcademicException.class,
				() -> service.updateSyllabus(
						prepared.subjectId,
						prepared.syllabusId,
						new PatchSyllabusRequest(
								null, "nope", null, null, null, null, null, null, null, null, null, null, null, null, null),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.SYLLABUS_PUBLISHED_IMMUTABLE, mutate.getCode());

		AcademicException structure = assertThrows(
				AcademicException.class,
				() -> service.replaceStructure(prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq()));
		assertEquals(AcademicErrorCode.SYLLABUS_PUBLISHED_IMMUTABLE, structure.getCode());

		UUID emptyId = service.createSyllabus(
						prepared.subjectId,
						new CreateSyllabusRequest(
								null, "empty", null, null, null, null, null, null, null, null, null, null, null, null, null),
						admin,
						auditReq())
				.id();
		AcademicException empty = assertThrows(
				AcademicException.class, () -> service.publish(prepared.subjectId, emptyId, admin, auditReq()));
		assertEquals(AcademicErrorCode.SYLLABUS_PUBLISH_INVALID, empty.getCode());
		assertEquals(SyllabusStatus.DRAFT, store.syllabi.get(emptyId).getStatus());
	}

	@Test
	void detailResponseKeepsPhaseAndDeliverableOrder() {
		PreparedSyllabus prepared = draft("SWT301");
		service.replaceStructure(prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq());
		SyllabusDetailResponse detail = service.getSyllabus(prepared.subjectId, prepared.syllabusId);
		assertEquals(List.of("LO1", "LO2"), detail.learningOutcomes().stream().map(o -> o.code()).toList());
		assertEquals(List.of("BLACK_BOX_TESTING", "REQUIREMENTS"), detail.learningUnits().stream().map(u -> u.code()).toList());
		assertEquals(List.of("ELICITATION", "ANALYSIS"), detail.phases().stream().map(p -> p.code()).toList());
		assertEquals("REQUIREMENT_ANALYSIS", detail.phases().get(0).activities().get(0).code());
	}

	@Test
	void inactiveStatusIsAuditedSeparately() {
		UUID subjectId = service.createSubject(new CreateSubjectRequest("SWP391", "SWP", null), admin, auditReq()).id();
		service.updateSubject(
				subjectId, new PatchSubjectRequest(null, null, null, SubjectStatus.INACTIVE), admin, auditReq());
		assertEquals(SubjectStatus.INACTIVE, store.subjects.get(subjectId).getStatus());
		assertNull(store.subjects.get(subjectId).getDeletedAt());
		verify(audit, times(1))
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(AcademicCatalogService.SUBJECT_STATUS_CHANGED),
						eq("subject"),
						eq(subjectId),
						any(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void publishEmptySyllabusIsRejectedWithoutPublishAudit() {
		PreparedSyllabus prepared = draft("SWR302");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.publish(prepared.subjectId, prepared.syllabusId, admin, auditReq()));
		assertEquals(AcademicErrorCode.SYLLABUS_PUBLISH_INVALID, ex.getCode());
		verify(audit, never())
				.record(
						any(),
						any(),
						any(),
						eq(AcademicCatalogService.SYLLABUS_PUBLISHED),
						any(),
						any(),
						any(),
						any(),
						any(),
						any(),
						any(),
						any(),
						any());
	}

	@Test
	void unknownLearningUnitOutcomeLeavesPreviousStructure() {
		PreparedSyllabus prepared = draft("SWT301");
		service.replaceStructure(prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.replaceStructure(
						prepared.subjectId,
						prepared.syllabusId,
						new SyllabusStructureRequest(
								List.of(new LearningOutcomeInput("LO1", "Analyze", null, 1)),
								List.of(new LearningUnitInput("BLACK_BOX_TESTING", "Black-box Testing", null, 1, List.of("LO9"))),
								List.of(new PhaseInput("TEST_DESIGN", "Test Design", null, 1, List.of("LO1"), List.of(), List.of()))),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.LEARNING_OUTCOME_REFERENCE_INVALID, ex.getCode());
		assertEquals(2, store.listOutcomes(prepared.syllabusId).size());
		assertEquals("BLACK_BOX_TESTING", store.listLearningUnits(prepared.syllabusId).get(0).getCode());
		assertEquals(2, store.listUnitOutcomeLinks(prepared.syllabusId).size());
	}

	@Test
	void publishedSyllabusRejectsLearningUnitMutation() {
		PreparedSyllabus prepared = draft("SWT301");
		service.replaceStructure(prepared.subjectId, prepared.syllabusId, validStructure(), admin, auditReq());
		service.publish(prepared.subjectId, prepared.syllabusId, admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.replaceStructure(
						prepared.subjectId,
						prepared.syllabusId,
						new SyllabusStructureRequest(
								List.of(new LearningOutcomeInput("LO1", "Analyze", null, 1)),
								List.of(new LearningUnitInput("WHITE_BOX_TESTING", "White-box Testing", null, 1, List.of("LO1"))),
								List.of(new PhaseInput("TEST_EXECUTION", "Test Execution", null, 1, List.of("LO1"), List.of(), List.of()))),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.SYLLABUS_PUBLISHED_IMMUTABLE, ex.getCode());
		assertEquals("BLACK_BOX_TESTING", store.listLearningUnits(prepared.syllabusId).get(0).getCode());
	}

	@Test
	void structureReturnedInDeterministicOrderIndexOrder() {
		PreparedSyllabus prepared = draft("SWT301");
		service.replaceStructure(
				prepared.subjectId,
				prepared.syllabusId,
				new SyllabusStructureRequest(
						List.of(
								new LearningOutcomeInput("LO2", "Design test cases", null, 2),
								new LearningOutcomeInput("LO1", "Analyze requirements", null, 1)),
						List.of(
								new LearningUnitInput("WHITE_BOX_TESTING", "White-box", null, 2, List.of("LO2")),
								new LearningUnitInput("BLACK_BOX_TESTING", "Black-box", null, 1, List.of("LO2"))),
						List.of(
								new PhaseInput("TEST_EXECUTION", "Execution", null, 2, List.of("LO2"), List.of(), List.of()),
								new PhaseInput("TEST_DESIGN", "Design", null, 1, List.of("LO1"), List.of(), List.of()))),
				admin,
				auditReq());
		SyllabusDetailResponse detail = service.getSyllabus(prepared.subjectId, prepared.syllabusId);
		assertEquals(List.of("LO1", "LO2"), detail.learningOutcomes().stream().map(o -> o.code()).toList());
		assertEquals(List.of("BLACK_BOX_TESTING", "WHITE_BOX_TESTING"), detail.learningUnits().stream().map(u -> u.code()).toList());
		assertEquals(List.of("TEST_DESIGN", "TEST_EXECUTION"), detail.phases().stream().map(p -> p.code()).toList());
	}

	@Test
	void reactivatingOrdinaryInactiveSubjectLeavesDeletedAtNull() {
		UUID subjectId = service.createSubject(new CreateSubjectRequest("SWP391", "SWP", null), admin, auditReq()).id();
		service.updateSubject(
				subjectId, new PatchSubjectRequest(null, null, null, SubjectStatus.INACTIVE), admin, auditReq());
		assertNull(store.subjects.get(subjectId).getDeletedAt());
		service.updateSubject(
				subjectId, new PatchSubjectRequest(null, null, null, SubjectStatus.ACTIVE), admin, auditReq());
		assertEquals(SubjectStatus.ACTIVE, store.subjects.get(subjectId).getStatus());
		assertNull(store.subjects.get(subjectId).getDeletedAt());
	}

	@Test
	void legacyDeletedSubjectIsInactiveAndCannotBecomeActive() {
		UUID subjectId = service.createSubject(new CreateSubjectRequest("SWP391", "SWP", null), admin, auditReq()).id();
		var subject = store.subjects.get(subjectId);
		subject.setStatus(SubjectStatus.INACTIVE);
		subject.setDeletedAt(java.time.LocalDateTime.now());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.updateSubject(
						subjectId, new PatchSubjectRequest(null, null, null, SubjectStatus.ACTIVE), admin, auditReq()));
		assertEquals(AcademicErrorCode.SUBJECT_STATUS_INVALID, ex.getCode());
		assertEquals(SubjectStatus.INACTIVE, store.subjects.get(subjectId).getStatus());
		assertNotNull(store.subjects.get(subjectId).getDeletedAt());
	}

	private PreparedSyllabus draft(String code) {
		UUID subjectId = service.createSubject(new CreateSubjectRequest(code, code, null), admin, auditReq()).id();
		UUID syllabusId = service.createSyllabus(
						subjectId,
						new CreateSyllabusRequest(
								null, "2026-v1", null, null, null, null, null, null, null, null, null, null, null, null, null),
						admin,
						auditReq())
				.id();
		return new PreparedSyllabus(subjectId, syllabusId);
	}

	static SyllabusStructureRequest validStructure() {
		return new SyllabusStructureRequest(
				List.of(
						new LearningOutcomeInput("LO1", "Analyze software requirements", null, 1),
						new LearningOutcomeInput("LO2", "Design a software solution", null, 2)),
				List.of(
						new LearningUnitInput(
								"BLACK_BOX_TESTING", "Black-box Testing", "Design test cases without internals", 1, List.of("LO2")),
						new LearningUnitInput("REQUIREMENTS", "Requirements", null, 2, List.of("LO1"))),
				List.of(
						new PhaseInput(
								"ELICITATION",
								"Elicitation",
								null,
								1,
								List.of("LO1"),
								List.of(new ActivityInput("REQUIREMENT_ANALYSIS", "Analyze requirements", null, 1)),
								List.of(new DeliverableInput(
										"REQUIREMENT_SPECIFICATION",
										"Requirement Specification",
										null,
										1,
										List.of("LO1")))),
						new PhaseInput(
								"ANALYSIS",
								"Analysis",
								null,
								2,
								List.of("LO2"),
								List.of(),
								List.of())));
	}

	private static AcademicCatalogService.AuditRequest auditReq() {
		return new AcademicCatalogService.AuditRequest("req-1", "127.0.0.1", "JUnit");
	}

	private record PreparedSyllabus(UUID subjectId, UUID syllabusId) {}
}
