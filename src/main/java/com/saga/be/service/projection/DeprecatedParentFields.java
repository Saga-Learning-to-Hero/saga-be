package com.saga.be.service.projection;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Compatibility for the pre-Jira-parent request fields. {@code parentTaskId} / {@code clearParent}
 * used to set a SAGA-only parent; SAGA now has one parent relation, the Jira parent, set through
 * {@code jiraParentTaskId} / {@code clearJiraParent}. The old names are still read as aliases of the
 * new ones -- each use is logged so the alias can be removed once no client sends it -- and a
 * request mixing them in a contradictory way is refused instead of guessing.
 */
final class DeprecatedParentFields {

	private static final Logger log = LoggerFactory.getLogger(DeprecatedParentFields.class);

	private DeprecatedParentFields() {}

	/**
	 * @return the Jira parent task id asked for (null = none); clearing is read by the caller
	 * @throws AcademicException 400 REQUEST_INVALID when the old and new fields disagree, or a parent
	 *     is both set and cleared
	 */
	static UUID resolve(UUID jiraParentTaskId, UUID parentTaskId, boolean clearJiraParent, boolean clearParent, String endpoint) {
		if (parentTaskId != null || clearParent) {
			log.warn("deprecated request field used endpoint={} field={}", endpoint, parentTaskId != null ? "parentTaskId" : "clearParent");
		}
		if (jiraParentTaskId != null && parentTaskId != null && !Objects.equals(jiraParentTaskId, parentTaskId)) {
			throw invalid("parentTaskId (deprecated) and jiraParentTaskId name different parents; send jiraParentTaskId only.");
		}
		UUID requested = jiraParentTaskId != null ? jiraParentTaskId : parentTaskId;
		if (requested != null && (clearJiraParent || clearParent)) {
			throw invalid("clearJiraParent cannot be combined with jiraParentTaskId.");
		}
		return requested;
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
