package com.saga.be.service.contribution;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A Subtask has no story points of its own. The number entered in Jira's story-point field is a
 * share of the parent, on a 1–10 scale: {@code 6} means 60% of the parent's story points.
 */
public final class SubtaskPercentPolicy {

	public static final int MIN_POINTS = 1;
	public static final int MAX_POINTS = 10;
	public static final int MAX_PERCENT = 100;

	private static final MathContext MATH = ContributionSliceWeights.MATH;

	private SubtaskPercentPolicy() {}

	public static boolean inRange(Integer storyPoints) {
		return storyPoints != null && storyPoints >= MIN_POINTS && storyPoints <= MAX_POINTS;
	}

	/** {@code 6} → {@code 60}. */
	public static int percent(int storyPoints) {
		return storyPoints * 10;
	}

	/**
	 * Parent ceiling times the subtask's percent. A null parent story point ceilings at {@code 1}.
	 * A subtask outside 1–10 contributes nothing.
	 */
	public static BigDecimal share(Integer parentStoryPoint, Integer subtaskStoryPoint) {
		if (!inRange(subtaskStoryPoint)) {
			return BigDecimal.ZERO;
		}
		BigDecimal ceiling = parentStoryPoint == null ? BigDecimal.ONE : BigDecimal.valueOf(parentStoryPoint.longValue());
		return ceiling.multiply(BigDecimal.valueOf(subtaskStoryPoint.longValue()), MATH).divide(BigDecimal.TEN, MATH);
	}

	/** Sum of in-range sibling percents, plus this entry, must be at most 100. */
	public static void requireRoom(Integer storyPoints, int usedPercent) {
		if (!inRange(storyPoints)) {
			throw invalid(
					"A subtask story point must be a whole number from 1 to 10. 6 means 60% of the parent.",
					Map.of("minPoints", MIN_POINTS, "maxPoints", MAX_POINTS));
		}
		int requested = percent(storyPoints);
		int total = usedPercent + requested;
		if (total > MAX_PERCENT) {
			throw invalid(
					"Subtask shares of this parent would total "
							+ total
							+ "%. They must total at most 100%.",
					Map.of(
							"usedPercent", usedPercent,
							"requestedPercent", requested,
							"maxPercent", MAX_PERCENT));
		}
	}

	private static IntegrationException invalid(String message, Map<String, Integer> details) {
		return new IntegrationException(
				IntegrationErrorCode.TASK_SUBTASK_PERCENT_INVALID, HttpStatus.BAD_REQUEST, message, details);
	}
}
