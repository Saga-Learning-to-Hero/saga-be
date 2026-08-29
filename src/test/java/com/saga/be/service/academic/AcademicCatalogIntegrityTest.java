package com.saga.be.service.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saga.be.dto.academic.CreateSubjectRequest;
import com.saga.be.dto.academic.CreateSyllabusRequest;
import com.saga.be.entity.academic.SyllabusDeliverableLearningOutcome;
import com.saga.be.entity.academic.SyllabusExpectedActivity;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusLearningOutcome;
import com.saga.be.entity.academic.SyllabusLearningUnit;
import com.saga.be.entity.academic.SyllabusLearningUnitOutcome;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.academic.SyllabusPhaseLearningOutcome;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.audit.AuditService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AcademicCatalogIntegrityTest {

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
	void activityCannotReferencePhaseFromAnotherSyllabus() {
		TwoSyllabi pair = twoPublishedStructures();
		SyllabusExpectedActivity activity = new SyllabusExpectedActivity();
		activity.setId(UUID.randomUUID());
		activity.setSyllabusVersion(store.syllabi.get(pair.left()));
		activity.setPhase(store.listPhases(pair.right()).get(0));
		activity.setCode("MISMATCHED_ACTIVITY");
		activity.setName("Mismatched");
		activity.setOrderIndex(1);
		assertRejected(pair.left(), List.of(activity), List.of(), List.of(), List.of(), List.of());
		assertEquals("ELICITATION", store.listPhases(pair.left()).get(0).getCode());
	}

	@Test
	void deliverableCannotBelongToMismatchedSyllabusPhase() {
		TwoSyllabi pair = twoPublishedStructures();
		SyllabusExpectedDeliverable deliverable = new SyllabusExpectedDeliverable();
		deliverable.setId(UUID.randomUUID());
		deliverable.setSyllabusVersion(store.syllabi.get(pair.left()));
		deliverable.setPhase(store.listPhases(pair.right()).get(0));
		deliverable.setCode("MISMATCHED_DELIVERABLE");
		deliverable.setName("Mismatched");
		deliverable.setOrderIndex(1);
		assertRejected(pair.left(), List.of(), List.of(deliverable), List.of(), List.of(), List.of());
		assertEquals("REQUIREMENT_SPECIFICATION", store.listDeliverables(pair.left()).get(0).getCode());
	}

	@Test
	void phaseCannotMapToOutcomeFromAnotherSyllabus() {
		TwoSyllabi pair = twoPublishedStructures();
		SyllabusPhaseLearningOutcome link = new SyllabusPhaseLearningOutcome();
		link.setId(UUID.randomUUID());
		link.setSyllabusVersion(store.syllabi.get(pair.left()));
		link.setPhase(store.listPhases(pair.left()).get(0));
		link.setLearningOutcome(store.listOutcomes(pair.right()).get(0));
		assertRejected(pair.left(), List.of(), List.of(), List.of(link), List.of(), List.of());
		assertEquals("LO1", store.listPhaseOutcomeLinks(pair.left()).get(0).getLearningOutcome().getCode());
	}

	@Test
	void deliverableCannotMapToOutcomeFromAnotherSyllabus() {
		TwoSyllabi pair = twoPublishedStructures();
		SyllabusDeliverableLearningOutcome link = new SyllabusDeliverableLearningOutcome();
		link.setId(UUID.randomUUID());
		link.setSyllabusVersion(store.syllabi.get(pair.left()));
		link.setDeliverable(store.listDeliverables(pair.left()).get(0));
		link.setLearningOutcome(store.listOutcomes(pair.right()).get(0));
		assertRejected(pair.left(), List.of(), List.of(), List.of(), List.of(link), List.of());
		assertEquals("LO1", store.listDeliverableOutcomeLinks(pair.left()).get(0).getLearningOutcome().getCode());
	}

	@Test
	void learningUnitCannotMapToOutcomeFromAnotherSyllabus() {
		TwoSyllabi pair = twoPublishedStructures();
		SyllabusLearningUnitOutcome link = new SyllabusLearningUnitOutcome();
		link.setId(UUID.randomUUID());
		link.setSyllabusVersion(store.syllabi.get(pair.left()));
		link.setLearningUnit(store.listLearningUnits(pair.left()).get(0));
		link.setLearningOutcome(store.listOutcomes(pair.right()).get(0));
		assertRejected(pair.left(), List.of(), List.of(), List.of(), List.of(), List.of(link));
		assertEquals("LO2", store.listUnitOutcomeLinks(pair.left()).get(0).getLearningOutcome().getCode());
	}

	private void assertRejected(
			UUID targetSyllabusId,
			List<SyllabusExpectedActivity> activities,
			List<SyllabusExpectedDeliverable> deliverables,
			List<SyllabusPhaseLearningOutcome> phaseLinks,
			List<SyllabusDeliverableLearningOutcome> deliverableLinks,
			List<SyllabusLearningUnitOutcome> unitLinks) {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> store.replaceStructure(
						targetSyllabusId,
						List.<SyllabusLearningOutcome>of(),
						List.<SyllabusLearningUnit>of(),
						List.<SyllabusPhase>of(),
						activities,
						deliverables,
						phaseLinks,
						deliverableLinks,
						unitLinks));
		assertEquals(AcademicErrorCode.ACADEMIC_STRUCTURE_INVALID, ex.getCode());
	}

	private TwoSyllabi twoPublishedStructures() {
		UUID leftSubject = service.createSubject(new CreateSubjectRequest("SWT301", "Testing", null), admin, auditReq())
				.id();
		UUID rightSubject = service.createSubject(new CreateSubjectRequest("SWR302", "Requirements", null), admin, auditReq())
				.id();
		UUID left = service.createSyllabus(leftSubject, emptyDraft("2026-v1"), admin, auditReq()).id();
		UUID right = service.createSyllabus(rightSubject, emptyDraft("2026-v1"), admin, auditReq()).id();
		service.replaceStructure(leftSubject, left, AcademicCatalogServiceTest.validStructure(), admin, auditReq());
		service.replaceStructure(rightSubject, right, AcademicCatalogServiceTest.validStructure(), admin, auditReq());
		return new TwoSyllabi(left, right);
	}

	private static CreateSyllabusRequest emptyDraft(String version) {
		return new CreateSyllabusRequest(
				null, version, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	private static AcademicCatalogService.AuditRequest auditReq() {
		return new AcademicCatalogService.AuditRequest("req-1", "127.0.0.1", "JUnit");
	}

	private record TwoSyllabi(UUID left, UUID right) {}
}
