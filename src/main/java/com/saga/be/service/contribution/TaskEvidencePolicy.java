package com.saga.be.service.contribution;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.service.contribution.ReservedContributionMarkerClassifier.Outcome;
import java.util.List;
import java.util.Set;

/**
 * What proof a task needs, decided by its SAGA contribution label -- the same markers contribution
 * scoring uses, so a warning never contradicts the score:
 *
 * <ul>
 *   <li>{@code saga:code}, {@code saga:test} -> at least one linked non-merge commit;
 *   <li>{@code saga:document} (or {@code saga:doc}), {@code saga:research} -> at least one document
 *       proof: a SAGA-uploaded file, a web link, or a Jira attachment (research output is notes,
 *       comparisons and references, proven like a document, never by code).
 * </ul>
 *
 * A task carrying several markers needs the union (code + document -> commit AND document). Only a
 * DONE task can be missing proof; a DONE task with no marker is {@link Status#UNLABELED}: SAGA cannot
 * tell what proof it needs, and it earns no criterion credit until labelled.
 */
public final class TaskEvidencePolicy {

	public enum Status {
		/** Not DONE yet: nothing is required so far. */
		NOT_DONE,
		/** DONE and every required proof is present. */
		SATISFIED,
		MISSING_COMMIT,
		MISSING_DOCUMENT,
		MISSING_COMMIT_AND_DOCUMENT,
		/** DONE with no SAGA label. */
		UNLABELED
	}

	public record Result(List<String> categories, boolean requiresCommit, boolean requiresDocument, Status status) {
		public boolean missingCommit() {
			return status == Status.MISSING_COMMIT || status == Status.MISSING_COMMIT_AND_DOCUMENT;
		}

		public boolean missingDocument() {
			return status == Status.MISSING_DOCUMENT || status == Status.MISSING_COMMIT_AND_DOCUMENT;
		}

		/** A required proof is absent (UNLABELED is a separate, softer signal). */
		public boolean missingProof() {
			return missingCommit() || missingDocument();
		}
	}

	private TaskEvidencePolicy() {}

	public static boolean requiresCommit(List<String> labels) {
		Set<Outcome> markers = ReservedContributionMarkerClassifier.markers(labels);
		return markers.contains(Outcome.CODE) || markers.contains(Outcome.TEST);
	}

	public static boolean requiresDocument(List<String> labels) {
		Set<Outcome> markers = ReservedContributionMarkerClassifier.markers(labels);
		return markers.contains(Outcome.DOCUMENT) || markers.contains(Outcome.RESEARCH);
	}

	/**
	 * @param commitEvidence linked commits that count as proof (non-merge)
	 * @param documentEvidence uploaded files + web links + Jira attachments
	 */
	public static Result evaluate(TaskStatus status, List<String> labels, long commitEvidence, long documentEvidence) {
		Set<Outcome> markers = ReservedContributionMarkerClassifier.markers(labels);
		List<String> categories = markers.stream().map(Outcome::name).toList();
		boolean needsCommit = markers.contains(Outcome.CODE) || markers.contains(Outcome.TEST);
		boolean needsDocument = markers.contains(Outcome.DOCUMENT) || markers.contains(Outcome.RESEARCH);
		Status result;
		if (status != TaskStatus.DONE) {
			result = Status.NOT_DONE;
		} else if (markers.isEmpty()) {
			result = Status.UNLABELED;
		} else {
			boolean commitMissing = needsCommit && commitEvidence <= 0;
			boolean documentMissing = needsDocument && documentEvidence <= 0;
			if (commitMissing && documentMissing) {
				result = Status.MISSING_COMMIT_AND_DOCUMENT;
			} else if (commitMissing) {
				result = Status.MISSING_COMMIT;
			} else if (documentMissing) {
				result = Status.MISSING_DOCUMENT;
			} else {
				result = Status.SATISFIED;
			}
		}
		return new Result(categories, needsCommit, needsDocument, result);
	}
}
