package com.saga.be.service.contribution;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.assessment.ProjectGroupWeightConfig;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public record ContributionSliceWeights(
		BigDecimal code, BigDecimal test, BigDecimal document, BigDecimal research) {

	public static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal QUARTER = new BigDecimal("0.25");

	public static ContributionSliceWeights equalQuarters() {
		return new ContributionSliceWeights(QUARTER, QUARTER, QUARTER, QUARTER);
	}

	public static ContributionSliceWeights fromCourse(Course course) {
		if (course == null) {
			return equalQuarters();
		}
		return fromPercents(
				course.getCodeContributionWeight(),
				course.getTestContributionWeight(),
				course.getDocumentContributionWeight(),
				course.getResearchContributionWeight());
	}

	public static ContributionSliceWeights fromProjectConfig(ProjectGroupWeightConfig config) {
		if (config == null) {
			return equalQuarters();
		}
		return normalizeUnit(config.getCodeWeight(), config.getTestWeight(), config.getDocumentWeight(), config.getResearchWeight());
	}

	public static ContributionSliceWeights fromPercents(Double code, Double test, Double document, Double research) {
		return normalizeUnit(
				percentToRatio(code), percentToRatio(test), percentToRatio(document), percentToRatio(research));
	}

	public static ContributionSliceWeights normalizeUnit(
			BigDecimal code, BigDecimal test, BigDecimal document, BigDecimal research) {
		BigDecimal c = zeroIfNull(code);
		BigDecimal t = zeroIfNull(test);
		BigDecimal d = zeroIfNull(document);
		BigDecimal r = zeroIfNull(research);
		BigDecimal sum = c.add(t, MATH).add(d, MATH).add(r, MATH);
		if (sum.compareTo(BigDecimal.ZERO) == 0) {
			return equalQuarters();
		}
		return new ContributionSliceWeights(
				c.divide(sum, MATH), t.divide(sum, MATH), d.divide(sum, MATH), r.divide(sum, MATH));
	}

	public BigDecimal asPercent(BigDecimal ratio) {
		return zeroIfNull(ratio).multiply(HUNDRED, MATH);
	}

	private static BigDecimal percentToRatio(Double value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(value).divide(HUNDRED, MATH);
	}

	private static BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}
}
