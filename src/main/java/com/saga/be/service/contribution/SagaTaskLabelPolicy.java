package com.saga.be.service.contribution;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Which labels SAGA lets a student put on a task. Only the four reserved contribution markers are
 * offered, and a task should carry at most one of them: {@link ReservedContributionMarkerClassifier}
 * scores a task with two markers as AMBIGUOUS, which earns no criterion credit at all.
 *
 * <p>Create: every label must be a marker, at most one. Patch: labels the task already has in the
 * local projection are kept verbatim (never silently stripped from Jira); only newly added labels
 * must be markers, and adding one may not leave the task with more than one marker.
 */
public final class SagaTaskLabelPolicy {

	public static final List<String> ALLOWED = List.of(
			ReservedContributionMarkerClassifier.CODE,
			ReservedContributionMarkerClassifier.TEST,
			ReservedContributionMarkerClassifier.DOCUMENT,
			ReservedContributionMarkerClassifier.RESEARCH);

	/** Spellings older clients sent for a marker; written to Jira as the canonical marker. */
	private static final Map<String, String> ALIASES = Map.of(
			"saga:doc", ReservedContributionMarkerClassifier.DOCUMENT,
			"saga:docs", ReservedContributionMarkerClassifier.DOCUMENT);

	private SagaTaskLabelPolicy() {}

	/** Canonical marker for {@code raw} (case-insensitive, aliases resolved), or null if not a marker. */
	public static String canonicalMarker(String raw) {
		if (raw == null) {
			return null;
		}
		String label = raw.trim().toLowerCase(Locale.ROOT);
		if (ALLOWED.contains(label)) {
			return label;
		}
		return ALIASES.get(label);
	}

	public static List<String> forCreate(List<String> requested) {
		if (requested == null) {
			return null;
		}
		List<String> result = new ArrayList<>();
		for (String raw : requested) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String marker = canonicalMarker(raw);
			if (marker == null) {
				throw notAllowed("Only SAGA labels can be added to a task: " + String.join(", ", ALLOWED) + ".");
			}
			if (!result.contains(marker)) {
				result.add(marker);
			}
		}
		requireAtMostOneMarker(result);
		return result;
	}

	public static List<String> forPatch(List<String> existing, List<String> requested) {
		if (requested == null) {
			return null;
		}
		Map<String, String> existingByKey = new LinkedHashMap<>();
		if (existing != null) {
			for (String label : existing) {
				if (label != null && !label.isBlank()) {
					existingByKey.putIfAbsent(label.trim().toLowerCase(Locale.ROOT), label);
				}
			}
		}
		List<String> result = new ArrayList<>();
		boolean markerAdded = false;
		for (String raw : requested) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String kept = existingByKey.get(raw.trim().toLowerCase(Locale.ROOT));
			String label;
			if (kept != null) {
				label = kept;
			} else {
				label = canonicalMarker(raw);
				if (label == null) {
					throw notAllowed("Only SAGA labels can be added to a task: " + String.join(", ", ALLOWED)
							+ ". Existing Jira labels are kept but new ones cannot be added.");
				}
				markerAdded = true;
			}
			if (result.stream().noneMatch(label::equalsIgnoreCase)) {
				result.add(label);
			}
		}
		if (markerAdded) {
			requireAtMostOneMarker(result);
		}
		return result;
	}

	private static void requireAtMostOneMarker(List<String> labels) {
		long markers = labels.stream().map(SagaTaskLabelPolicy::canonicalMarker).filter(m -> m != null).distinct().count();
		if (markers > 1) {
			throw notAllowed("A task can carry only one SAGA label; a task with several is not counted for contribution.");
		}
	}

	private static IntegrationException notAllowed(String message) {
		return new IntegrationException(
				IntegrationErrorCode.TASK_LABEL_NOT_ALLOWED,
				HttpStatus.BAD_REQUEST,
				message,
				Map.of("allowedLabels", ALLOWED));
	}
}
