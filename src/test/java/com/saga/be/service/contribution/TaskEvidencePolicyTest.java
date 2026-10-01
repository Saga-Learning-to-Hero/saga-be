package com.saga.be.service.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.service.contribution.TaskEvidencePolicy.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEvidencePolicyTest {

	@Test
	void codeAndTestNeedACommitAndNothingElse() {
		assertThat(status(List.of("saga:code"), 0, 5)).isEqualTo(Status.MISSING_COMMIT);
		assertThat(status(List.of("saga:test"), 1, 0)).isEqualTo(Status.SATISFIED);
	}

	@Test
	void documentAndResearchNeedADocumentProofAndCommitsNeverStandIn() {
		assertThat(status(List.of("saga:document"), 3, 0)).isEqualTo(Status.MISSING_DOCUMENT);
		assertThat(status(List.of("saga:doc"), 0, 2)).isEqualTo(Status.SATISFIED);
		assertThat(status(List.of("saga:research"), 0, 0)).isEqualTo(Status.MISSING_DOCUMENT);
		assertThat(status(List.of("saga:research"), 0, 1)).isEqualTo(Status.SATISFIED);
	}

	@Test
	void severalMarkersNeedTheUnionOfTheirProofs() {
		assertThat(status(List.of("saga:code", "saga:document"), 0, 0)).isEqualTo(Status.MISSING_COMMIT_AND_DOCUMENT);
		assertThat(status(List.of("saga:code", "saga:document"), 1, 0)).isEqualTo(Status.MISSING_DOCUMENT);
		assertThat(status(List.of("saga:code", "saga:document"), 1, 1)).isEqualTo(Status.SATISFIED);
	}

	@Test
	void unlabelledDoneTaskIsFlaggedSeparatelyAndUnfinishedTasksNeedNothingYet() {
		assertThat(status(List.of("backend"), 0, 0)).isEqualTo(Status.UNLABELED);
		assertThat(status(List.of(), 3, 3)).isEqualTo(Status.UNLABELED);
		assertThat(TaskEvidencePolicy.evaluate(TaskStatus.IN_REVIEW, List.of("saga:code"), 0, 0).status())
				.isEqualTo(Status.NOT_DONE);
	}

	@Test
	void resultReportsCategoriesAndRequirements() {
		TaskEvidencePolicy.Result result =
				TaskEvidencePolicy.evaluate(TaskStatus.DONE, List.of("saga:research", "saga:test", "frontend"), 0, 0);

		assertThat(result.categories()).containsExactly("TEST", "RESEARCH");
		assertThat(result.requiresCommit()).isTrue();
		assertThat(result.requiresDocument()).isTrue();
		assertThat(result.missingCommit()).isTrue();
		assertThat(result.missingDocument()).isTrue();
	}

	@Test
	void markersAreExactAndCaseSensitiveLikeScoring() {
		assertThat(status(List.of("SAGA:CODE"), 0, 0)).isEqualTo(Status.UNLABELED);
		assertThat(status(List.of("saga:code-extra"), 0, 0)).isEqualTo(Status.UNLABELED);
	}

	@Test
	void parentWithSubtasksNeedsNeitherCommitNorDocument() {
		TaskEvidencePolicy.Result result = TaskEvidencePolicy.evaluate(
				TaskStatus.DONE, List.of("saga:code", "saga:document"), 0, 0, true);

		assertThat(result.status()).isEqualTo(Status.SATISFIED);
		assertThat(result.requiresCommit()).isFalse();
		assertThat(result.requiresDocument()).isFalse();
		assertThat(result.missingProof()).isFalse();
	}

	private static Status status(List<String> labels, long commits, long documents) {
		return TaskEvidencePolicy.evaluate(TaskStatus.DONE, labels, commits, documents).status();
	}
}
