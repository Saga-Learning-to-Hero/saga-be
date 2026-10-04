package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyllabusExpectedDeliverableRepository;
import com.saga.be.repository.SyllabusPhaseRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Course context for a commit review: the course's pinned syllabus (its phases and expected
 * deliverables), so the AI can say which deliverable a commit serves. Bounded (saga-ai accepts at
 * most 100 evidence items per request) and optional: a course without a pinned syllabus simply
 * adds nothing, it never blocks the review.
 */
@Component
@Profile("!test")
public class AiCommitReviewContextBuilder {

	static final int MAX_PHASES = 6;
	static final int MAX_DELIVERABLES = 12;
	private static final int MAX_TEXT = 600;
	private static final Logger log = LoggerFactory.getLogger(AiCommitReviewContextBuilder.class);

	private final ProjectRepository projects;
	private final SyllabusPhaseRepository phases;
	private final SyllabusExpectedDeliverableRepository deliverables;
	private final ObjectMapper mapper;

	public AiCommitReviewContextBuilder(
			ProjectRepository projects,
			SyllabusPhaseRepository phases,
			SyllabusExpectedDeliverableRepository deliverables,
			ObjectMapper mapper) {
		this.projects = projects;
		this.phases = phases;
		this.deliverables = deliverables;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true)
	public List<AiEvidenceDraft> build(UUID projectId) {
		try {
			Project project = projects.findWithPinnedSyllabus(projectId).orElse(null);
			SubjectSyllabusVersion version = project == null || project.getCourse() == null ? null : project.getCourse().getSyllabusVersion();
			if (version == null) return List.of();
			List<AiEvidenceDraft> out = new ArrayList<>();
			Map<String, Object> versionPayload = new LinkedHashMap<>();
			versionPayload.put("syllabusVersionId", version.getId());
			versionPayload.put("versionLabel", text(version.getVersionLabel()));
			versionPayload.put("subjectCode", project.getCourse().getSubject() == null ? "" : text(project.getCourse().getSubject().getSubjectCode()));
			versionPayload.put("purpose", "Course syllabus: which phase/deliverable the commit and its task serve. Not a coding standard.");
			out.add(row(AiEvidenceType.SYLLABUS_VERSION, "syllabus:" + version.getId(), versionPayload));
			for (SyllabusPhase phase : phases.findBySyllabusVersion_IdOrderByOrderIndexAsc(version.getId()).stream().limit(MAX_PHASES).toList()) {
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("phaseId", phase.getId());
				payload.put("code", text(phase.getCode()));
				payload.put("name", text(phase.getName()));
				payload.put("description", text(phase.getDescription()));
				payload.put("orderIndex", phase.getOrderIndex());
				out.add(row(AiEvidenceType.SYLLABUS_PHASE, "phase:" + phase.getId(), payload));
			}
			for (SyllabusExpectedDeliverable item : deliverables.findBySyllabusVersion_IdOrderByOrderIndexAsc(version.getId()).stream().limit(MAX_DELIVERABLES).toList()) {
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("deliverableId", item.getId());
				payload.put("code", text(item.getCode()));
				payload.put("name", text(item.getName()));
				payload.put("description", text(item.getDescription()));
				payload.put("phaseId", item.getPhaseId());
				out.add(row(AiEvidenceType.SYLLABUS_DELIVERABLE, "deliverable:" + item.getId(), payload));
			}
			return List.copyOf(out);
		} catch (RuntimeException ex) {
			log.warn("commit review syllabus context skipped projectId={} type={}", projectId, ex.getClass().getSimpleName());
			return List.of();
		}
	}

	private AiEvidenceDraft row(AiEvidenceType type, String ref, Map<String, Object> payload) {
		try {
			return new AiEvidenceDraft(type, ref, mapper.writeValueAsString(payload), null);
		} catch (Exception ex) {
			throw new IllegalStateException("AI evidence serialization failed", ex);
		}
	}

	private static String text(String value) {
		if (value == null) return "";
		return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
	}
}
