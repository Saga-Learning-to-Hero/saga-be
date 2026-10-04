package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyllabusExpectedDeliverableRepository;
import com.saga.be.repository.SyllabusPhaseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiCommitReviewContextBuilderTest {

	private final UUID projectId = UUID.randomUUID();
	private ProjectRepository projects;
	private SyllabusPhaseRepository phases;
	private SyllabusExpectedDeliverableRepository deliverables;
	private AiCommitReviewContextBuilder builder;
	private SubjectSyllabusVersion version;

	@BeforeEach
	void setUp() {
		projects = mock(ProjectRepository.class);
		phases = mock(SyllabusPhaseRepository.class);
		deliverables = mock(SyllabusExpectedDeliverableRepository.class);
		builder = new AiCommitReviewContextBuilder(projects, phases, deliverables, new ObjectMapper());
		version = new SubjectSyllabusVersion();
		version.setId(UUID.randomUUID());
		version.setVersionLabel("v2026");
		Subject subject = new Subject();
		subject.setSubjectCode("SWP391");
		Course course = new Course();
		course.setSubject(subject);
		course.setSyllabusVersion(version);
		Project project = new Project();
		project.setId(projectId);
		project.setCourse(course);
		when(projects.findWithPinnedSyllabus(projectId)).thenReturn(Optional.of(project));
	}

	@Test
	void theSyllabusPhasesAndDeliverablesAreSentBounded() {
		List<SyllabusPhase> manyPhases = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			SyllabusPhase phase = new SyllabusPhase();
			phase.setId(UUID.randomUUID());
			phase.setCode("P" + i);
			phase.setName("Giai đoạn " + i);
			phase.setDescription("x".repeat(2_000));
			phase.setOrderIndex(i);
			manyPhases.add(phase);
		}
		List<SyllabusExpectedDeliverable> manyDeliverables = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			SyllabusExpectedDeliverable item = new SyllabusExpectedDeliverable();
			item.setId(UUID.randomUUID());
			item.setCode("D" + i);
			item.setName("Sản phẩm " + i);
			manyDeliverables.add(item);
		}
		when(phases.findBySyllabusVersion_IdOrderByOrderIndexAsc(version.getId())).thenReturn(manyPhases);
		when(deliverables.findBySyllabusVersion_IdOrderByOrderIndexAsc(version.getId())).thenReturn(manyDeliverables);

		List<AiEvidenceDraft> rows = builder.build(projectId);

		assertThat(rows).filteredOn(r -> r.type() == AiEvidenceType.SYLLABUS_VERSION).singleElement()
				.satisfies(r -> assertThat(r.payloadJson()).contains("SWP391").contains("v2026"));
		assertThat(rows).filteredOn(r -> r.type() == AiEvidenceType.SYLLABUS_PHASE).hasSize(AiCommitReviewContextBuilder.MAX_PHASES);
		assertThat(rows).filteredOn(r -> r.type() == AiEvidenceType.SYLLABUS_DELIVERABLE).hasSize(AiCommitReviewContextBuilder.MAX_DELIVERABLES);
		assertThat(rows).allSatisfy(r -> assertThat(r.payloadJson().length()).isLessThan(2_000));
	}

	@Test
	void noPinnedSyllabusAddsNothing_andAFailureNeverBlocksTheReview() {
		Project bare = new Project();
		bare.setCourse(new Course());
		when(projects.findWithPinnedSyllabus(projectId)).thenReturn(Optional.of(bare));
		assertThat(builder.build(projectId)).isEmpty();

		when(projects.findWithPinnedSyllabus(projectId)).thenThrow(new RuntimeException("db"));
		assertThat(builder.build(projectId)).isEmpty();
	}
}
