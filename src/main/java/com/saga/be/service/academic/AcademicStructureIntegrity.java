package com.saga.be.service.academic;

import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusDeliverableLearningOutcome;
import com.saga.be.entity.academic.SyllabusExpectedActivity;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusLearningUnitOutcome;
import com.saga.be.entity.academic.SyllabusPhaseLearningOutcome;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class AcademicStructureIntegrity {

	private AcademicStructureIntegrity() {}

	static void requireSameSyllabus(UUID syllabusId, SubjectSyllabusVersion version, String label) {
		if (version == null || version.getId() == null || !syllabusId.equals(version.getId())) {
			throw new AcademicException(
					AcademicErrorCode.ACADEMIC_STRUCTURE_INVALID,
					HttpStatus.BAD_REQUEST,
					label + " belongs to a different syllabus version.");
		}
	}

	static void requireGraph(
			UUID syllabusId,
			List<SyllabusExpectedActivity> activities,
			List<SyllabusExpectedDeliverable> deliverables,
			List<SyllabusPhaseLearningOutcome> phaseLinks,
			List<SyllabusDeliverableLearningOutcome> deliverableLinks,
			List<SyllabusLearningUnitOutcome> unitLinks) {
		for (SyllabusExpectedActivity activity : activities) {
			requireSameSyllabus(syllabusId, activity.getSyllabusVersion(), "Activity");
			requireSameSyllabus(syllabusId, activity.getPhase().getSyllabusVersion(), "Activity phase");
		}
		for (SyllabusExpectedDeliverable deliverable : deliverables) {
			requireSameSyllabus(syllabusId, deliverable.getSyllabusVersion(), "Deliverable");
			requireSameSyllabus(syllabusId, deliverable.getPhase().getSyllabusVersion(), "Deliverable phase");
		}
		for (SyllabusPhaseLearningOutcome link : phaseLinks) {
			requireSameSyllabus(syllabusId, link.getPhase().getSyllabusVersion(), "Phase");
			requireSameSyllabus(syllabusId, link.getLearningOutcome().getSyllabusVersion(), "Phase outcome");
		}
		for (SyllabusDeliverableLearningOutcome link : deliverableLinks) {
			requireSameSyllabus(syllabusId, link.getDeliverable().getSyllabusVersion(), "Deliverable");
			requireSameSyllabus(syllabusId, link.getLearningOutcome().getSyllabusVersion(), "Deliverable outcome");
		}
		for (SyllabusLearningUnitOutcome link : unitLinks) {
			requireSameSyllabus(syllabusId, link.getLearningUnit().getSyllabusVersion(), "Learning unit");
			requireSameSyllabus(syllabusId, link.getLearningOutcome().getSyllabusVersion(), "Learning unit outcome");
		}
	}
}
