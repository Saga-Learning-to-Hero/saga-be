package com.saga.be.service.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saga.be.dto.contribution.ContributionSliceWeightsRequest;
import com.saga.be.dto.contribution.ProjectGroupWeightsRequest;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ContributionWeightValidationTest {

	@Test
	void courseWeightsMustSumTo100() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> CourseContributionWeightService.validateCoursePercents(
						new ContributionSliceWeightsRequest(bd("40"), bd("10"), bd("15"), bd("30"))));
		assertEquals(AcademicErrorCode.CONTRIBUTION_WEIGHTS_INVALID, ex.getCode());
	}

	@Test
	void projectGroupWeightsMustSumToOne() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> ProjectGroupWeightConfigService.validateUnitWeights(
						new ProjectGroupWeightsRequest(null, bd("0.4"), bd("0.1"), bd("0.15"), bd("0.30"), null)));
		assertEquals(AcademicErrorCode.CONTRIBUTION_WEIGHTS_INVALID, ex.getCode());
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}
}
