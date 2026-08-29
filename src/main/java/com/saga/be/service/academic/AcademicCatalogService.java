package com.saga.be.service.academic;

import com.saga.be.dto.academic.ActivityInput;
import com.saga.be.dto.academic.ActivityResponse;
import com.saga.be.dto.academic.CreateSubjectRequest;
import com.saga.be.dto.academic.CreateSyllabusRequest;
import com.saga.be.dto.academic.DeliverableInput;
import com.saga.be.dto.academic.DeliverableResponse;
import com.saga.be.dto.academic.LearningOutcomeInput;
import com.saga.be.dto.academic.LearningOutcomeResponse;
import com.saga.be.dto.academic.LearningUnitInput;
import com.saga.be.dto.academic.LearningUnitResponse;
import com.saga.be.dto.academic.PatchSubjectRequest;
import com.saga.be.dto.academic.PatchSyllabusRequest;
import com.saga.be.dto.academic.PhaseInput;
import com.saga.be.dto.academic.PhaseResponse;
import com.saga.be.dto.academic.SubjectResponse;
import com.saga.be.dto.academic.SyllabusDetailResponse;
import com.saga.be.dto.academic.SyllabusStructureRequest;
import com.saga.be.dto.academic.SyllabusSummaryResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusDeliverableLearningOutcome;
import com.saga.be.entity.academic.SyllabusExpectedActivity;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusLearningOutcome;
import com.saga.be.entity.academic.SyllabusLearningUnit;
import com.saga.be.entity.academic.SyllabusLearningUnitOutcome;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.academic.SyllabusPhaseLearningOutcome;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.audit.AuditService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class AcademicCatalogService {

	public static final String SUBJECT_CREATED = "SUBJECT_CREATED";
	public static final String SUBJECT_UPDATED = "SUBJECT_UPDATED";
	public static final String SUBJECT_STATUS_CHANGED = "SUBJECT_STATUS_CHANGED";
	public static final String SYLLABUS_CREATED = "SYLLABUS_CREATED";
	public static final String SYLLABUS_UPDATED = "SYLLABUS_UPDATED";
	public static final String SYLLABUS_STRUCTURE_UPDATED = "SYLLABUS_STRUCTURE_UPDATED";
	public static final String SYLLABUS_PUBLISHED = "SYLLABUS_PUBLISHED";
	public static final String SYLLABUS_ARCHIVED = "SYLLABUS_ARCHIVED";

	private final AcademicCatalogStore store;
	private final AuditService audit;

	public AcademicCatalogService(AcademicCatalogStore store, AuditService audit) {
		this.store = store;
		this.audit = audit;
	}

	public record AuditRequest(String requestId, String ip, String userAgent) {}

	@Transactional
	public SubjectResponse createSubject(CreateSubjectRequest request, UserAccount actor, AuditRequest auditRequest) {
		String code = normalizeSubjectCode(request.code());
		String nameEnglish = requireName(request.nameEnglish(), "English name is required.");
		if (store.existsSubjectCode(code)) {
			throw conflict(AcademicErrorCode.SUBJECT_CODE_DUPLICATE, "Subject code already exists.");
		}
		Subject subject = new Subject();
		subject.setSubjectCode(code);
		subject.setName(nameEnglish);
		subject.setNameVietnamese(trimToNull(request.nameVietnamese()));
		subject.setStatus(SubjectStatus.ACTIVE);
		subject.setDeletedAt(null);
		Subject saved = store.saveSubject(subject);
		audit(
				actor,
				SUBJECT_CREATED,
				"subject",
				saved.getId(),
				null,
				subjectSnapshot(saved),
				Map.of("code", saved.getSubjectCode()),
				auditRequest);
		return toSubjectResponse(saved, List.of());
	}

	@Transactional(readOnly = true)
	public List<SubjectResponse> listSubjects(String code, SubjectStatus status, String search) {
		String normalized = StringUtils.hasText(code) ? normalizeSubjectCode(code) : null;
		return store.listSubjects(normalized, status, blankToNull(search)).stream()
				.map(subject -> toSubjectResponse(subject, List.of()))
				.toList();
	}

	@Transactional(readOnly = true)
	public SubjectResponse getSubject(UUID subjectId) {
		Subject subject = requireSubject(subjectId);
		return toSubjectResponse(subject, store.listSyllabi(subjectId).stream().map(this::toSummary).toList());
	}

	@Transactional
	public SubjectResponse updateSubject(
			UUID subjectId, PatchSubjectRequest request, UserAccount actor, AuditRequest auditRequest) {
		Subject subject = requireSubject(subjectId);
		Map<String, Object> before = subjectSnapshot(subject);
		boolean changed = false;
		if (request.code() != null) {
			String code = normalizeSubjectCode(request.code());
			if (!code.equals(subject.getSubjectCode())) {
				if (store.existsSubjectCode(code)) {
					throw conflict(AcademicErrorCode.SUBJECT_CODE_DUPLICATE, "Subject code already exists.");
				}
				subject.setSubjectCode(code);
				changed = true;
			}
		}
		if (request.nameEnglish() != null) {
			subject.setName(requireName(request.nameEnglish(), "English name is required."));
			changed = true;
		}
		if (request.nameVietnamese() != null) {
			subject.setNameVietnamese(trimToNull(request.nameVietnamese()));
			changed = true;
		}
		SubjectStatus previousStatus = subject.getStatus();
		if (request.status() != null && request.status() != previousStatus) {
			applyStatus(subject, request.status());
			audit(
					actor,
					SUBJECT_STATUS_CHANGED,
					"subject",
					subject.getId(),
					Map.of("status", previousStatus.name()),
					Map.of("status", subject.getStatus().name()),
					Map.of("code", subject.getSubjectCode()),
					auditRequest);
			changed = true;
		}
		if (changed) {
			subject = store.saveSubject(subject);
			audit(
					actor,
					SUBJECT_UPDATED,
					"subject",
					subject.getId(),
					before,
					subjectSnapshot(subject),
					Map.of("code", subject.getSubjectCode()),
					auditRequest);
		}
		return getSubject(subject.getId());
	}

	@Transactional
	public SyllabusSummaryResponse createSyllabus(
			UUID subjectId, CreateSyllabusRequest request, UserAccount actor, AuditRequest auditRequest) {
		Subject subject = requireSubject(subjectId);
		String versionLabel = normalizeVersionLabel(request.versionLabel());
		if (store.versionLabelTaken(subjectId, versionLabel, null)) {
			throw conflict(AcademicErrorCode.SYLLABUS_VERSION_LABEL_DUPLICATE, "Version label already exists for this subject.");
		}
		String externalId = trimToNull(request.externalSyllabusId());
		if (externalId != null && store.externalIdTaken(subjectId, externalId, null)) {
			throw conflict(AcademicErrorCode.SYLLABUS_EXTERNAL_ID_DUPLICATE, "External syllabus id already exists for this subject.");
		}
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel(versionLabel);
		syllabus.setExternalSyllabusId(externalId);
		syllabus.setStatus(SyllabusStatus.DRAFT);
		applyMetadata(syllabus, request);
		SubjectSyllabusVersion saved = store.saveSyllabus(syllabus);
		audit(
				actor,
				SYLLABUS_CREATED,
				"subject_syllabus_version",
				saved.getId(),
				null,
				syllabusSnapshot(saved),
				Map.of("subjectId", subjectId.toString(), "versionLabel", saved.getVersionLabel()),
				auditRequest);
		return toSummary(saved);
	}

	@Transactional(readOnly = true)
	public List<SyllabusSummaryResponse> listSyllabi(UUID subjectId) {
		requireSubject(subjectId);
		return store.listSyllabi(subjectId).stream().map(this::toSummary).toList();
	}

	@Transactional(readOnly = true)
	public SyllabusDetailResponse getSyllabus(UUID subjectId, UUID syllabusId) {
		return toDetail(requireSyllabus(subjectId, syllabusId));
	}

	@Transactional
	public SyllabusSummaryResponse updateSyllabus(
			UUID subjectId,
			UUID syllabusId,
			PatchSyllabusRequest request,
			UserAccount actor,
			AuditRequest auditRequest) {
		SubjectSyllabusVersion syllabus = requireDraft(subjectId, syllabusId);
		Map<String, Object> before = syllabusSnapshot(syllabus);
		if (request.versionLabel() != null) {
			String versionLabel = normalizeVersionLabel(request.versionLabel());
			if (store.versionLabelTaken(subjectId, versionLabel, syllabus.getId())) {
				throw conflict(
						AcademicErrorCode.SYLLABUS_VERSION_LABEL_DUPLICATE, "Version label already exists for this subject.");
			}
			syllabus.setVersionLabel(versionLabel);
		}
		if (request.externalSyllabusId() != null) {
			String externalId = trimToNull(request.externalSyllabusId());
			if (externalId != null && store.externalIdTaken(subjectId, externalId, syllabus.getId())) {
				throw conflict(
						AcademicErrorCode.SYLLABUS_EXTERNAL_ID_DUPLICATE,
						"External syllabus id already exists for this subject.");
			}
			syllabus.setExternalSyllabusId(externalId);
		}
		if (request.titleEnglish() != null) {
			syllabus.setTitleEnglish(trimToNull(request.titleEnglish()));
		}
		if (request.titleVietnamese() != null) {
			syllabus.setTitleVietnamese(trimToNull(request.titleVietnamese()));
		}
		if (request.credits() != null) {
			syllabus.setCredits(request.credits());
		}
		if (request.level() != null) {
			syllabus.setLevel(trimToNull(request.level()));
		}
		if (request.learningTeachingMethod() != null) {
			syllabus.setLearningTeachingMethod(trimToNull(request.learningTeachingMethod()));
		}
		if (request.timeAllocation() != null) {
			syllabus.setTimeAllocation(trimToNull(request.timeAllocation()));
		}
		if (request.prerequisites() != null) {
			syllabus.setPrerequisites(trimToNull(request.prerequisites()));
		}
		if (request.description() != null) {
			syllabus.setDescription(trimToNull(request.description()));
		}
		if (request.studentDuties() != null) {
			syllabus.setStudentDuties(trimToNull(request.studentDuties()));
		}
		if (request.tools() != null) {
			syllabus.setTools(trimToNull(request.tools()));
		}
		if (request.textbooks() != null) {
			syllabus.setTextbooks(trimToNull(request.textbooks()));
		}
		if (request.referenceMaterials() != null) {
			syllabus.setReferenceMaterials(trimToNull(request.referenceMaterials()));
		}
		if (request.gradingScale() != null) {
			syllabus.setGradingScale(trimToNull(request.gradingScale()));
		}
		SubjectSyllabusVersion saved = store.saveSyllabus(syllabus);
		audit(
				actor,
				SYLLABUS_UPDATED,
				"subject_syllabus_version",
				saved.getId(),
				before,
				syllabusSnapshot(saved),
				Map.of("subjectId", subjectId.toString()),
				auditRequest);
		return toSummary(saved);
	}

	@Transactional
	public SyllabusDetailResponse replaceStructure(
			UUID subjectId,
			UUID syllabusId,
			SyllabusStructureRequest request,
			UserAccount actor,
			AuditRequest auditRequest) {
		SubjectSyllabusVersion syllabus = requireDraft(subjectId, syllabusId);
		ValidatedStructure validated = validateStructure(request, false);
		persistStructure(syllabus, validated);
		audit(
				actor,
				SYLLABUS_STRUCTURE_UPDATED,
				"subject_syllabus_version",
				syllabus.getId(),
				null,
				Map.of(
						"learningOutcomeCount", validated.outcomes().size(),
						"learningUnitCount", validated.learningUnits().size(),
						"phaseCount", validated.phases().size()),
				Map.of("subjectId", subjectId.toString()),
				auditRequest);
		return toDetail(syllabus);
	}

	@Transactional
	public SyllabusDetailResponse publish(
			UUID subjectId, UUID syllabusId, UserAccount actor, AuditRequest auditRequest) {
		SubjectSyllabusVersion syllabus = requireDraft(subjectId, syllabusId);
		SyllabusStructureRequest current = currentStructure(syllabus);
		validateStructure(current, true);
		if (store.listOutcomes(syllabus.getId()).isEmpty() || store.listPhases(syllabus.getId()).isEmpty()) {
			throw badRequest(AcademicErrorCode.SYLLABUS_PUBLISH_INVALID, "Published syllabus needs at least one learning outcome and one phase.");
		}
		Map<String, Object> before = syllabusSnapshot(syllabus);
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		syllabus.setPublishedAt(LocalDateTime.now());
		SubjectSyllabusVersion saved = store.saveSyllabus(syllabus);
		audit(
				actor,
				SYLLABUS_PUBLISHED,
				"subject_syllabus_version",
				saved.getId(),
				before,
				syllabusSnapshot(saved),
				Map.of("subjectId", subjectId.toString(), "versionLabel", saved.getVersionLabel()),
				auditRequest);
		return toDetail(saved);
	}

	@Transactional
	public SyllabusDetailResponse archive(
			UUID subjectId, UUID syllabusId, UserAccount actor, AuditRequest auditRequest) {
		SubjectSyllabusVersion syllabus = requireSyllabus(subjectId, syllabusId);
		if (syllabus.getStatus() != SyllabusStatus.PUBLISHED) {
			throw conflict(AcademicErrorCode.SYLLABUS_ARCHIVE_INVALID, "Only a published syllabus can be archived.");
		}
		Map<String, Object> before = syllabusSnapshot(syllabus);
		syllabus.setStatus(SyllabusStatus.ARCHIVED);
		SubjectSyllabusVersion saved = store.saveSyllabus(syllabus);
		audit(
				actor,
				SYLLABUS_ARCHIVED,
				"subject_syllabus_version",
				saved.getId(),
				before,
				syllabusSnapshot(saved),
				Map.of("subjectId", subjectId.toString()),
				auditRequest);
		return toDetail(saved);
	}

	private void persistStructure(SubjectSyllabusVersion syllabus, ValidatedStructure validated) {
		Map<String, SyllabusLearningOutcome> outcomeByCode = new LinkedHashMap<>();
		List<SyllabusLearningOutcome> outcomeEntities = new ArrayList<>();
		for (LearningOutcomeInput input : validated.outcomes()) {
			SyllabusLearningOutcome outcome = new SyllabusLearningOutcome();
			outcome.setId(UUID.randomUUID());
			outcome.setSyllabusVersion(syllabus);
			outcome.setCode(normalizeAcademicCode(input.code()));
			outcome.setName(input.name().trim());
			outcome.setDescription(trimToNull(input.description()));
			outcome.setOrderIndex(input.orderIndex());
			outcomeEntities.add(outcome);
			outcomeByCode.put(outcome.getCode(), outcome);
		}
		List<SyllabusPhase> phaseEntities = new ArrayList<>();
		List<SyllabusExpectedActivity> activityEntities = new ArrayList<>();
		List<SyllabusExpectedDeliverable> deliverableEntities = new ArrayList<>();
		List<SyllabusPhaseLearningOutcome> phaseLinks = new ArrayList<>();
		List<SyllabusDeliverableLearningOutcome> deliverableLinks = new ArrayList<>();
		List<SyllabusLearningUnit> unitEntities = new ArrayList<>();
		List<SyllabusLearningUnitOutcome> unitLinks = new ArrayList<>();
		for (LearningUnitInput unitInput : validated.learningUnits()) {
			SyllabusLearningUnit unit = new SyllabusLearningUnit();
			unit.setId(UUID.randomUUID());
			unit.setSyllabusVersion(syllabus);
			unit.setCode(normalizeAcademicCode(unitInput.code()));
			unit.setName(unitInput.name().trim());
			unit.setDescription(trimToNull(unitInput.description()));
			unit.setOrderIndex(unitInput.orderIndex());
			unitEntities.add(unit);
			for (String outcomeCode : orEmpty(unitInput.learningOutcomeCodes())) {
				SyllabusLearningUnitOutcome link = new SyllabusLearningUnitOutcome();
				link.setId(UUID.randomUUID());
				link.setSyllabusVersion(syllabus);
				link.setLearningUnit(unit);
				link.setLearningOutcome(outcomeByCode.get(normalizeAcademicCode(outcomeCode)));
				unitLinks.add(link);
			}
		}
		for (PhaseInput phaseInput : validated.phases()) {
			SyllabusPhase phase = new SyllabusPhase();
			phase.setId(UUID.randomUUID());
			phase.setSyllabusVersion(syllabus);
			phase.setCode(normalizeAcademicCode(phaseInput.code()));
			phase.setName(phaseInput.name().trim());
			phase.setDescription(trimToNull(phaseInput.description()));
			phase.setOrderIndex(phaseInput.orderIndex());
			phaseEntities.add(phase);
			for (String outcomeCode : orEmpty(phaseInput.learningOutcomeCodes())) {
				SyllabusPhaseLearningOutcome link = new SyllabusPhaseLearningOutcome();
				link.setId(UUID.randomUUID());
				link.setSyllabusVersion(syllabus);
				link.setPhase(phase);
				link.setLearningOutcome(outcomeByCode.get(normalizeAcademicCode(outcomeCode)));
				phaseLinks.add(link);
			}
			for (ActivityInput activityInput : orEmpty(phaseInput.activities())) {
				SyllabusExpectedActivity activity = new SyllabusExpectedActivity();
				activity.setId(UUID.randomUUID());
				activity.setSyllabusVersion(syllabus);
				activity.setPhase(phase);
				activity.setCode(normalizeAcademicCode(activityInput.code()));
				activity.setName(activityInput.name().trim());
				activity.setDescription(trimToNull(activityInput.description()));
				activity.setOrderIndex(activityInput.orderIndex());
				activityEntities.add(activity);
			}
			for (DeliverableInput deliverableInput : orEmpty(phaseInput.deliverables())) {
				SyllabusExpectedDeliverable deliverable = new SyllabusExpectedDeliverable();
				deliverable.setId(UUID.randomUUID());
				deliverable.setSyllabusVersion(syllabus);
				deliverable.setPhase(phase);
				deliverable.setCode(normalizeAcademicCode(deliverableInput.code()));
				deliverable.setName(deliverableInput.name().trim());
				deliverable.setDescription(trimToNull(deliverableInput.description()));
				deliverable.setOrderIndex(deliverableInput.orderIndex());
				deliverableEntities.add(deliverable);
				for (String outcomeCode : orEmpty(deliverableInput.learningOutcomeCodes())) {
					SyllabusDeliverableLearningOutcome link = new SyllabusDeliverableLearningOutcome();
					link.setId(UUID.randomUUID());
					link.setSyllabusVersion(syllabus);
					link.setDeliverable(deliverable);
					link.setLearningOutcome(outcomeByCode.get(normalizeAcademicCode(outcomeCode)));
					deliverableLinks.add(link);
				}
			}
		}
		store.replaceStructure(
				syllabus.getId(),
				outcomeEntities,
				unitEntities,
				phaseEntities,
				activityEntities,
				deliverableEntities,
				phaseLinks,
				deliverableLinks,
				unitLinks);
	}

	private ValidatedStructure validateStructure(SyllabusStructureRequest request, boolean forPublish) {
		if (request == null || request.learningOutcomes() == null || request.phases() == null) {
			throw badRequest(AcademicErrorCode.ACADEMIC_STRUCTURE_INVALID, "Learning outcomes and phases are required.");
		}
		List<LearningOutcomeInput> outcomes = request.learningOutcomes();
		List<LearningUnitInput> learningUnits = request.learningUnits() == null ? List.of() : request.learningUnits();
		List<PhaseInput> phases = request.phases();
		if (forPublish && (outcomes.isEmpty() || phases.isEmpty())) {
			throw badRequest(
					AcademicErrorCode.SYLLABUS_PUBLISH_INVALID,
					"Published syllabus needs at least one learning outcome and one phase.");
		}
		Set<String> outcomeCodes = uniqueCodes(outcomes.stream().map(LearningOutcomeInput::code).toList(), "learning outcome");
		uniqueOrderIndexes(outcomes.stream().map(LearningOutcomeInput::orderIndex).toList(), "learning outcome");
		for (LearningOutcomeInput outcome : outcomes) {
			requireName(outcome.name(), "Learning outcome name is required.");
			requireOrder(outcome.orderIndex(), "learning outcome");
		}
		uniqueCodes(learningUnits.stream().map(LearningUnitInput::code).toList(), "learning unit");
		uniqueOrderIndexes(learningUnits.stream().map(LearningUnitInput::orderIndex).toList(), "learning unit");
		for (LearningUnitInput unit : learningUnits) {
			requireName(unit.name(), "Learning unit name is required.");
			requireOrder(unit.orderIndex(), "learning unit");
			for (String referenced : orEmpty(unit.learningOutcomeCodes())) {
				String code = normalizeAcademicCode(referenced);
				if (!outcomeCodes.contains(code)) {
					throw badRequest(
							AcademicErrorCode.LEARNING_OUTCOME_REFERENCE_INVALID,
							"Learning unit references unknown learning outcome code.");
				}
			}
		}
		uniqueCodes(phases.stream().map(PhaseInput::code).toList(), "phase");
		uniqueOrderIndexes(phases.stream().map(PhaseInput::orderIndex).toList(), "phase");
		Set<String> activityCodes = new HashSet<>();
		Set<String> deliverableCodes = new HashSet<>();
		for (PhaseInput phase : phases) {
			requireName(phase.name(), "Phase name is required.");
			requireOrder(phase.orderIndex(), "phase");
			for (String referenced : orEmpty(phase.learningOutcomeCodes())) {
				String code = normalizeAcademicCode(referenced);
				if (!outcomeCodes.contains(code)) {
					throw badRequest(
							AcademicErrorCode.LEARNING_OUTCOME_REFERENCE_INVALID,
							"Phase references unknown learning outcome code.");
				}
			}
			uniqueOrderIndexes(
					orEmpty(phase.activities()).stream().map(ActivityInput::orderIndex).toList(), "activity");
			uniqueOrderIndexes(
					orEmpty(phase.deliverables()).stream().map(DeliverableInput::orderIndex).toList(), "deliverable");
			for (ActivityInput activity : orEmpty(phase.activities())) {
				requireName(activity.name(), "Activity name is required.");
				requireOrder(activity.orderIndex(), "activity");
				String code = normalizeAcademicCode(activity.code());
				if (!activityCodes.add(code)) {
					throw conflict(AcademicErrorCode.ACADEMIC_CODE_DUPLICATE, "Activity code must be unique within the syllabus.");
				}
			}
			for (DeliverableInput deliverable : orEmpty(phase.deliverables())) {
				requireName(deliverable.name(), "Deliverable name is required.");
				requireOrder(deliverable.orderIndex(), "deliverable");
				String code = normalizeAcademicCode(deliverable.code());
				if (!deliverableCodes.add(code)) {
					throw conflict(
							AcademicErrorCode.ACADEMIC_CODE_DUPLICATE, "Deliverable code must be unique within the syllabus.");
				}
				for (String referenced : orEmpty(deliverable.learningOutcomeCodes())) {
					String outcomeCode = normalizeAcademicCode(referenced);
					if (!outcomeCodes.contains(outcomeCode)) {
						throw badRequest(
								AcademicErrorCode.LEARNING_OUTCOME_REFERENCE_INVALID,
								"Deliverable references unknown learning outcome code.");
					}
				}
			}
		}
		return new ValidatedStructure(outcomes, learningUnits, phases);
	}

	private SyllabusStructureRequest currentStructure(SubjectSyllabusVersion syllabus) {
		UUID id = syllabus.getId();
		List<SyllabusLearningOutcome> outcomes = store.listOutcomes(id);
		List<SyllabusLearningUnit> units = store.listLearningUnits(id);
		List<SyllabusPhase> phases = store.listPhases(id);
		List<SyllabusExpectedActivity> activities = store.listActivities(id);
		List<SyllabusExpectedDeliverable> deliverables = store.listDeliverables(id);
		Map<UUID, List<String>> unitOutcomes = store.listUnitOutcomeLinks(id).stream()
				.collect(Collectors.groupingBy(
						link -> link.getLearningUnit().getId(),
						Collectors.mapping(link -> link.getLearningOutcome().getCode(), Collectors.toList())));
		Map<UUID, List<String>> phaseOutcomes = store.listPhaseOutcomeLinks(id).stream()
				.collect(Collectors.groupingBy(
						link -> link.getPhase().getId(),
						Collectors.mapping(link -> link.getLearningOutcome().getCode(), Collectors.toList())));
		Map<UUID, List<String>> deliverableOutcomes = store.listDeliverableOutcomeLinks(id).stream()
				.collect(Collectors.groupingBy(
						link -> link.getDeliverable().getId(),
						Collectors.mapping(link -> link.getLearningOutcome().getCode(), Collectors.toList())));
		List<LearningOutcomeInput> outcomeInputs = outcomes.stream()
				.map(o -> new LearningOutcomeInput(o.getCode(), o.getName(), o.getDescription(), o.getOrderIndex()))
				.toList();
		List<LearningUnitInput> unitInputs = units.stream()
				.map(unit -> new LearningUnitInput(
						unit.getCode(),
						unit.getName(),
						unit.getDescription(),
						unit.getOrderIndex(),
						unitOutcomes.getOrDefault(unit.getId(), List.of())))
				.toList();
		List<PhaseInput> phaseInputs = phases.stream()
				.map(phase -> new PhaseInput(
						phase.getCode(),
						phase.getName(),
						phase.getDescription(),
						phase.getOrderIndex(),
						phaseOutcomes.getOrDefault(phase.getId(), List.of()),
						activities.stream()
								.filter(activity -> activity.getPhase().getId().equals(phase.getId()))
								.map(a -> new ActivityInput(a.getCode(), a.getName(), a.getDescription(), a.getOrderIndex()))
								.toList(),
						deliverables.stream()
								.filter(deliverable -> deliverable.getPhase().getId().equals(phase.getId()))
								.map(d -> new DeliverableInput(
										d.getCode(),
										d.getName(),
										d.getDescription(),
										d.getOrderIndex(),
										deliverableOutcomes.getOrDefault(d.getId(), List.of())))
								.toList()))
				.toList();
		return new SyllabusStructureRequest(outcomeInputs, unitInputs, phaseInputs);
	}

	private Subject requireSubject(UUID subjectId) {
		return store.findSubjectById(subjectId)
				.orElseThrow(() -> notFound(AcademicErrorCode.SUBJECT_NOT_FOUND, "Subject was not found."));
	}

	private SubjectSyllabusVersion requireSyllabus(UUID subjectId, UUID syllabusId) {
		requireSubject(subjectId);
		return store.findSyllabus(subjectId, syllabusId)
				.orElseThrow(() -> notFound(AcademicErrorCode.SYLLABUS_NOT_FOUND, "Syllabus version was not found."));
	}

	private SubjectSyllabusVersion requireDraft(UUID subjectId, UUID syllabusId) {
		SubjectSyllabusVersion syllabus = requireSyllabus(subjectId, syllabusId);
		if (syllabus.getStatus() != SyllabusStatus.DRAFT) {
			throw conflict(
					AcademicErrorCode.SYLLABUS_PUBLISHED_IMMUTABLE, "Only a draft syllabus can be changed.");
		}
		return syllabus;
	}

	private void applyStatus(Subject subject, SubjectStatus status) {
		if (status == SubjectStatus.ACTIVE && subject.getDeletedAt() != null) {
			throw conflict(
					AcademicErrorCode.SUBJECT_STATUS_INVALID,
					"A deleted subject cannot be ACTIVE.");
		}
		subject.setStatus(status);
	}

	private void applyMetadata(SubjectSyllabusVersion syllabus, CreateSyllabusRequest request) {
		syllabus.setTitleEnglish(trimToNull(request.titleEnglish()));
		syllabus.setTitleVietnamese(trimToNull(request.titleVietnamese()));
		syllabus.setCredits(request.credits());
		syllabus.setLevel(trimToNull(request.level()));
		syllabus.setLearningTeachingMethod(trimToNull(request.learningTeachingMethod()));
		syllabus.setTimeAllocation(trimToNull(request.timeAllocation()));
		syllabus.setPrerequisites(trimToNull(request.prerequisites()));
		syllabus.setDescription(trimToNull(request.description()));
		syllabus.setStudentDuties(trimToNull(request.studentDuties()));
		syllabus.setTools(trimToNull(request.tools()));
		syllabus.setTextbooks(trimToNull(request.textbooks()));
		syllabus.setReferenceMaterials(trimToNull(request.referenceMaterials()));
		syllabus.setGradingScale(trimToNull(request.gradingScale()));
	}

	private SubjectResponse toSubjectResponse(Subject subject, List<SyllabusSummaryResponse> syllabi) {
		return new SubjectResponse(
				subject.getId(),
				subject.getSubjectCode(),
				subject.getName(),
				subject.getNameVietnamese(),
				subject.getStatus() == null ? SubjectStatus.ACTIVE : subject.getStatus(),
				subject.getCreatedAt(),
				subject.getUpdatedAt(),
				syllabi);
	}

	private SyllabusSummaryResponse toSummary(SubjectSyllabusVersion syllabus) {
		return new SyllabusSummaryResponse(
				syllabus.getId(),
				syllabus.getSubject().getId(),
				syllabus.getExternalSyllabusId(),
				syllabus.getVersionLabel(),
				syllabus.getStatus(),
				syllabus.getTitleEnglish(),
				syllabus.getTitleVietnamese(),
				syllabus.getCredits(),
				syllabus.getPublishedAt(),
				syllabus.getCreatedAt(),
				syllabus.getUpdatedAt());
	}

	private SyllabusDetailResponse toDetail(SubjectSyllabusVersion syllabus) {
		UUID id = syllabus.getId();
		List<SyllabusLearningOutcome> outcomes = store.listOutcomes(id);
		List<SyllabusLearningUnit> units = store.listLearningUnits(id);
		List<SyllabusPhase> phases = store.listPhases(id);
		List<SyllabusExpectedActivity> activities = store.listActivities(id);
		List<SyllabusExpectedDeliverable> deliverables = store.listDeliverables(id);
		Map<UUID, List<String>> unitOutcomes = new HashMap<>();
		for (SyllabusLearningUnitOutcome link : store.listUnitOutcomeLinks(id)) {
			unitOutcomes
					.computeIfAbsent(link.getLearningUnit().getId(), ignored -> new ArrayList<>())
					.add(link.getLearningOutcome().getCode());
		}
		Map<UUID, List<String>> phaseOutcomes = new HashMap<>();
		for (SyllabusPhaseLearningOutcome link : store.listPhaseOutcomeLinks(id)) {
			phaseOutcomes
					.computeIfAbsent(link.getPhase().getId(), ignored -> new ArrayList<>())
					.add(link.getLearningOutcome().getCode());
		}
		Map<UUID, List<String>> deliverableOutcomes = new HashMap<>();
		for (SyllabusDeliverableLearningOutcome link : store.listDeliverableOutcomeLinks(id)) {
			deliverableOutcomes
					.computeIfAbsent(link.getDeliverable().getId(), ignored -> new ArrayList<>())
					.add(link.getLearningOutcome().getCode());
		}
		List<LearningOutcomeResponse> outcomeResponses = outcomes.stream()
				.map(o -> new LearningOutcomeResponse(o.getId(), o.getCode(), o.getName(), o.getDescription(), o.getOrderIndex()))
				.toList();
		List<LearningUnitResponse> unitResponses = units.stream()
				.map(unit -> new LearningUnitResponse(
						unit.getId(),
						unit.getCode(),
						unit.getName(),
						unit.getDescription(),
						unit.getOrderIndex(),
						List.copyOf(unitOutcomes.getOrDefault(unit.getId(), List.of()))))
				.toList();
		List<PhaseResponse> phaseResponses = phases.stream()
				.map(phase -> new PhaseResponse(
						phase.getId(),
						phase.getCode(),
						phase.getName(),
						phase.getDescription(),
						phase.getOrderIndex(),
						List.copyOf(phaseOutcomes.getOrDefault(phase.getId(), List.of())),
						activities.stream()
								.filter(activity -> activity.getPhase().getId().equals(phase.getId()))
								.sorted((a, b) -> Integer.compare(a.getOrderIndex(), b.getOrderIndex()))
								.map(a -> new ActivityResponse(
										a.getId(), a.getCode(), a.getName(), a.getDescription(), a.getOrderIndex()))
								.toList(),
						deliverables.stream()
								.filter(deliverable -> deliverable.getPhase().getId().equals(phase.getId()))
								.sorted((a, b) -> Integer.compare(a.getOrderIndex(), b.getOrderIndex()))
								.map(d -> new DeliverableResponse(
										d.getId(),
										d.getCode(),
										d.getName(),
										d.getDescription(),
										d.getOrderIndex(),
										List.copyOf(deliverableOutcomes.getOrDefault(d.getId(), List.of()))))
								.toList()))
				.toList();
		return new SyllabusDetailResponse(
				syllabus.getId(),
				syllabus.getSubject().getId(),
				syllabus.getSubject().getSubjectCode(),
				syllabus.getExternalSyllabusId(),
				syllabus.getVersionLabel(),
				syllabus.getStatus(),
				syllabus.getTitleEnglish(),
				syllabus.getTitleVietnamese(),
				syllabus.getCredits(),
				syllabus.getLevel(),
				syllabus.getLearningTeachingMethod(),
				syllabus.getTimeAllocation(),
				syllabus.getPrerequisites(),
				syllabus.getDescription(),
				syllabus.getStudentDuties(),
				syllabus.getTools(),
				syllabus.getTextbooks(),
				syllabus.getReferenceMaterials(),
				syllabus.getGradingScale(),
				syllabus.getPublishedAt(),
				syllabus.getCreatedAt(),
				syllabus.getUpdatedAt(),
				outcomeResponses,
				unitResponses,
				phaseResponses);
	}

	private Set<String> uniqueCodes(List<String> rawCodes, String label) {
		Set<String> codes = new HashSet<>();
		for (String raw : rawCodes) {
			String code = normalizeAcademicCode(raw);
			if (!codes.add(code)) {
				throw conflict(AcademicErrorCode.ACADEMIC_CODE_DUPLICATE, "Duplicate " + label + " code.");
			}
		}
		return codes;
	}

	private void uniqueOrderIndexes(List<Integer> indexes, String label) {
		Set<Integer> seen = new HashSet<>();
		for (Integer index : indexes) {
			requireOrder(index, label);
			if (!seen.add(index)) {
				throw badRequest(AcademicErrorCode.ORDER_INDEX_INVALID, "Duplicate " + label + " orderIndex.");
			}
		}
	}

	private void requireOrder(Integer orderIndex, String label) {
		if (orderIndex == null || orderIndex <= 0) {
			throw badRequest(AcademicErrorCode.ORDER_INDEX_INVALID, label + " orderIndex must be greater than 0.");
		}
	}

	private static String normalizeSubjectCode(String code) {
		if (!StringUtils.hasText(code)) {
			throw badRequest(AcademicErrorCode.SUBJECT_CODE_INVALID, "Subject code is required.");
		}
		return code.trim().toUpperCase();
	}

	private static String normalizeVersionLabel(String label) {
		if (!StringUtils.hasText(label)) {
			throw badRequest(AcademicErrorCode.SYLLABUS_VERSION_LABEL_INVALID, "Version label is required.");
		}
		return label.trim();
	}

	private static String normalizeAcademicCode(String code) {
		if (!StringUtils.hasText(code)) {
			throw badRequest(AcademicErrorCode.ACADEMIC_STRUCTURE_INVALID, "Academic code is required.");
		}
		return code.trim().toUpperCase();
	}

	private static String requireName(String name, String message) {
		if (!StringUtils.hasText(name)) {
			throw badRequest(AcademicErrorCode.ACADEMIC_STRUCTURE_INVALID, message);
		}
		return name.trim();
	}

	private static String trimToNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return value.trim();
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private static <T> List<T> orEmpty(List<T> values) {
		return values == null ? List.of() : values;
	}

	private Map<String, Object> subjectSnapshot(Subject subject) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("code", subject.getSubjectCode());
		snapshot.put("nameEnglish", subject.getName());
		snapshot.put("nameVietnamese", subject.getNameVietnamese());
		snapshot.put("status", subject.getStatus() == null ? null : subject.getStatus().name());
		return snapshot;
	}

	private Map<String, Object> syllabusSnapshot(SubjectSyllabusVersion syllabus) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("versionLabel", syllabus.getVersionLabel());
		snapshot.put("status", syllabus.getStatus() == null ? null : syllabus.getStatus().name());
		snapshot.put("externalSyllabusId", syllabus.getExternalSyllabusId());
		snapshot.put("credits", syllabus.getCredits());
		snapshot.put("textbooks", syllabus.getTextbooks());
		snapshot.put("referenceMaterials", syllabus.getReferenceMaterials());
		snapshot.put("publishedAt", syllabus.getPublishedAt() == null ? null : syllabus.getPublishedAt().toString());
		return snapshot;
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

	private static AcademicException badRequest(AcademicErrorCode code, String message) {
		return new AcademicException(code, HttpStatus.BAD_REQUEST, message);
	}

	private static AcademicException conflict(AcademicErrorCode code, String message) {
		return new AcademicException(code, HttpStatus.CONFLICT, message);
	}

	private static AcademicException notFound(AcademicErrorCode code, String message) {
		return new AcademicException(code, HttpStatus.NOT_FOUND, message);
	}

	private record ValidatedStructure(
			List<LearningOutcomeInput> outcomes, List<LearningUnitInput> learningUnits, List<PhaseInput> phases) {}
}
