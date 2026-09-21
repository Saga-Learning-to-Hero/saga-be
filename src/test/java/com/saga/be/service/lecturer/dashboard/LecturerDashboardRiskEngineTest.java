package com.saga.be.service.lecturer.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Risk;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.RiskReason;
import com.saga.be.entity.enums.LecturerDashboardRiskLevel;
import com.saga.be.entity.enums.LecturerDashboardRiskReasonCode;
import com.saga.be.service.lecturer.dashboard.LecturerDashboardRiskEngine.Signals;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LecturerDashboardRiskEngineTest {

	@Test
	void healthyWhenSignalsAreClear() {
		Risk risk = LecturerDashboardRiskEngine.evaluate(base());

		assertThat(risk.level()).isEqualTo(LecturerDashboardRiskLevel.HEALTHY);
		assertThat(risk.reasons()).isEmpty();
	}

	@Test
	void noProjectIsCriticalAndIgnoresOtherSignals() {
		Risk risk = LecturerDashboardRiskEngine.evaluate(new Signals(
				false,
				false,
				10,
				true,
				50.0,
				4,
				4,
				4,
				true,
				true,
				false,
				true,
				100.0,
				true,
				6L,
				0L,
				List.of(UUID.randomUUID())));

		assertThat(risk.level()).isEqualTo(LecturerDashboardRiskLevel.CRITICAL);
		assertThat(risk.reasons())
				.extracting(RiskReason::code)
				.containsExactly(LecturerDashboardRiskReasonCode.NO_PROJECT);
	}

	@Test
	void inactiveWarningAtThreeDaysAndCriticalAtFive() {
		assertThat(reasonLevel(withInactiveDays(2), LecturerDashboardRiskReasonCode.INACTIVE)).isNull();
		assertThat(reasonLevel(withInactiveDays(3), LecturerDashboardRiskReasonCode.INACTIVE))
				.isEqualTo(LecturerDashboardRiskLevel.WARNING);
		assertThat(reasonLevel(withInactiveDays(4), LecturerDashboardRiskReasonCode.INACTIVE))
				.isEqualTo(LecturerDashboardRiskLevel.WARNING);
		assertThat(reasonLevel(withInactiveDays(5), LecturerDashboardRiskReasonCode.INACTIVE))
				.isEqualTo(LecturerDashboardRiskLevel.CRITICAL);
	}

	@Test
	void unknownLastActivityIsDataUnavailableNotInactive() {
		Risk risk = LecturerDashboardRiskEngine.evaluate(new Signals(
				true, true, 12, false, 0.0, 0, 0, 0, false, false, true, false, 0.0, false, 0L, 0L, List.of()));

		assertThat(risk.level()).isEqualTo(LecturerDashboardRiskLevel.UNKNOWN);
		assertThat(risk.reasons())
				.extracting(RiskReason::code)
				.containsExactly(LecturerDashboardRiskReasonCode.DATA_UNAVAILABLE);
	}

	@Test
	void scheduleLagWarningAtTwentyAndCriticalAtThirtyFive() {
		assertThat(reasonLevel(withGap(19.99), LecturerDashboardRiskReasonCode.SCHEDULE_LAG)).isNull();
		assertThat(reasonLevel(withGap(20.0), LecturerDashboardRiskReasonCode.SCHEDULE_LAG))
				.isEqualTo(LecturerDashboardRiskLevel.WARNING);
		assertThat(reasonLevel(withGap(34.99), LecturerDashboardRiskReasonCode.SCHEDULE_LAG))
				.isEqualTo(LecturerDashboardRiskLevel.WARNING);
		assertThat(reasonLevel(withGap(35.0), LecturerDashboardRiskReasonCode.SCHEDULE_LAG))
				.isEqualTo(LecturerDashboardRiskLevel.CRITICAL);
	}

	@Test
	void noActiveSprintIsWarning() {
		Risk risk = LecturerDashboardRiskEngine.evaluate(new Signals(
				true, false, 0, true, 0.0, 0, 0, 0, false, false, true, false, 0.0, false, 0L, 0L, List.of()));

		assertThat(risk.level()).isEqualTo(LecturerDashboardRiskLevel.WARNING);
		assertThat(risk.reasons())
				.extracting(RiskReason::code)
				.containsExactly(LecturerDashboardRiskReasonCode.NO_ACTIVE_SPRINT);
	}

	private static LecturerDashboardRiskLevel reasonLevel(Signals signals, LecturerDashboardRiskReasonCode code) {
		return LecturerDashboardRiskEngine.evaluate(signals).reasons().stream()
				.filter(reason -> reason.code() == code)
				.map(RiskReason::severity)
				.findFirst()
				.orElse(null);
	}

	private static Signals withInactiveDays(int days) {
		return new Signals(
				true, true, days, true, 0.0, 0, 0, 0, false, false, true, false, 0.0, false, 0L, 0L, List.of());
	}

	private static Signals withGap(double gap) {
		return new Signals(
				true, true, 0, true, gap, 0, 0, 0, false, false, true, false, 0.0, false, 0L, 0L, List.of());
	}

	private static Signals base() {
		return new Signals(
				true, true, 0, true, 0.0, 0, 0, 0, false, false, true, false, 0.0, false, 0L, 0L, List.of());
	}
}
